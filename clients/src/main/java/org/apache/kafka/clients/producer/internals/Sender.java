/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.producer.internals;

import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.RequestCompletionHandler;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidMetadataException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.metrics.Measurable;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.clients.NetworkClientUtils;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.requests.InitProducerIdRequest;
import org.apache.kafka.common.requests.InitProducerIdResponse;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The background thread that handles the sending of produce requests to the Kafka cluster. This thread makes metadata
 * requests to renew its view of the cluster and then sends produce requests to the appropriate nodes.
 */
public class Sender implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Sender.class);

    /* the state of each nodes connection */
    private final KafkaClient client;

    /* the record accumulator that batches records */
    private final RecordAccumulator accumulator;

    /* the metadata for the client */
    private final Metadata metadata;

    /* the flag indicating whether the producer should guarantee the message order on the broker or not. */
    private final boolean guaranteeMessageOrder;

    /* the maximum request size to attempt to send to the server */
    private final int maxRequestSize;

    /* the number of acknowledgements to request from the server */
    private final short acks;

    /* the number of times to retry a failed request before giving up */
    private final int retries;

    /* the clock instance used for getting the time */
    private final Time time;

    /* true while the sender thread is still running */
    private volatile boolean running;

    /* true when the caller wants to ignore all unsent/inflight messages and force close.  */
    private volatile boolean forceClose;

    /* metrics */
    private final SenderMetrics sensors;

    /* the max time to wait for the server to respond to the request*/
    private final int requestTimeout;

    /* The max time to wait before retrying a request which has failed */
    private final long retryBackoffMs;

    /* current request API versions supported by the known brokers */
    private final ApiVersions apiVersions;

    /* all the state related to transactions, in particular the PID, epoch, and sequence numbers */
    private final TransactionManager transactionManager;

    public Sender(KafkaClient client,
                  Metadata metadata,
                  RecordAccumulator accumulator,
                  boolean guaranteeMessageOrder,
                  int maxRequestSize,
                  short acks,
                  int retries,
                  Metrics metrics,
                  Time time,
                  int requestTimeout,
                  long retryBackoffMs,
                  TransactionManager transactionManager,
                  ApiVersions apiVersions) {
        this.client = client;
        this.accumulator = accumulator;
        this.metadata = metadata;
        this.guaranteeMessageOrder = guaranteeMessageOrder;
        this.maxRequestSize = maxRequestSize;
        this.running = true;
        this.acks = acks;
        this.retries = retries;
        this.time = time;
        this.sensors = new SenderMetrics(metrics);
        this.requestTimeout = requestTimeout;
        this.retryBackoffMs = retryBackoffMs;
        this.apiVersions = apiVersions;
        this.transactionManager = transactionManager;
    }

    /**
     * The main run loop for the sender thread
     */
    public void run() {
        log.debug("Starting Kafka producer I/O thread.");

        // main loop, runs until close is called
        while (running) {
            try {
                run(time.milliseconds());
            } catch (Exception e) {
                log.error("Uncaught error in kafka producer I/O thread: ", e);
            }
        }

        log.debug("Beginning shutdown of Kafka producer I/O thread, sending remaining records.");

        // okay we stopped accepting requests but there may still be
        // requests in the accumulator or waiting for acknowledgment,
        // wait until these are completed.
        while (!forceClose && (this.accumulator.hasUnsent() || this.client.inFlightRequestCount() > 0)) {
            try {
                run(time.milliseconds());
            } catch (Exception e) {
                log.error("Uncaught error in kafka producer I/O thread: ", e);
            }
        }
        if (forceClose) {
            // We need to fail all the incomplete batches and wake up the threads waiting on
            // the futures.
            this.accumulator.abortIncompleteBatches();
        }
        try {
            this.client.close();
        } catch (Exception e) {
            log.error("Failed to close network client", e);
        }

        log.debug("Shutdown of Kafka producer I/O thread has completed.");
    }

    /**
     * Run a single iteration of sending
     * 
     * @param now The current POSIX time in milliseconds
     */
    void run(long now) {
        long pollTimeout = 0;
        if (!maybeSendTransactionalRequest(now))
            pollTimeout = sendProducerData(now);

        this.client.poll(pollTimeout, now);
    }


    private long sendProducerData(long now) {
        Cluster cluster = metadata.fetch();
        maybeWaitForPid();

        // get the list of partitions with data ready to send
        RecordAccumulator.ReadyCheckResult result = this.accumulator.ready(cluster, now);

        // if there are any partitions whose leaders are not known yet, force metadata update
        if (!result.unknownLeaderTopics.isEmpty()) {
            // The set of topics with unknown leader contains topics with leader election pending as well as
            // topics which may have expired. Add the topic again to metadata to ensure it is included
            // and request metadata update, since there are messages to send to the topic.
            for (String topic : result.unknownLeaderTopics)
                this.metadata.add(topic);
            this.metadata.requestUpdate();
        }

        // remove any nodes we aren't ready to send to
        Iterator<Node> iter = result.readyNodes.iterator();
        long notReadyTimeout = Long.MAX_VALUE;
        while (iter.hasNext()) {
            Node node = iter.next();
            if (!this.client.ready(node, now)) {
                iter.remove();
                notReadyTimeout = Math.min(notReadyTimeout, this.client.connectionDelay(node, now));
            }
        }

        // create produce requests
        Map<Integer, List<ProducerBatch>> batches = this.accumulator.drain(cluster, result.readyNodes,
                this.maxRequestSize, now);
        if (guaranteeMessageOrder) {
            // Mute all the partitions drained
            for (List<ProducerBatch> batchList : batches.values()) {
                for (ProducerBatch batch : batchList)
                    this.accumulator.mutePartition(batch.topicPartition);
            }
        }

        List<ProducerBatch> expiredBatches = this.accumulator.abortExpiredBatches(this.requestTimeout, now);

        boolean needsTransactionStateReset = false;
        // Reset the PID if an expired batch has previously been sent to the broker. Also update the metrics
        // for expired batches. see the documentation of @TransactionState.resetProducerId to understand why
        // we need to reset the producer id here.
        for (ProducerBatch expiredBatch : expiredBatches) {
            if (transactionManager != null && expiredBatch.inRetry()) {
                needsTransactionStateReset = true;
            }
            this.sensors.recordErrors(expiredBatch.topicPartition.topic(), expiredBatch.recordCount);
        }

        if (needsTransactionStateReset) {
            transactionManager.resetProducerId();
            return 0;
        }

        sensors.updateProduceRequestMetrics(batches);

        // If we have any nodes that are ready to send + have sendable data, poll with 0 timeout so this can immediately
        // loop and try sending more data. Otherwise, the timeout is determined by nodes that have partitions with data
        // that isn't yet sendable (e.g. lingering, backing off). Note that this specifically does not include nodes
        // with sendable data that aren't ready to send since they would cause busy looping.
        long pollTimeout = Math.min(result.nextReadyCheckDelayMs, notReadyTimeout);
        if (!result.readyNodes.isEmpty()) {
            log.trace("Nodes with data ready to send: {}", result.readyNodes);
            // if some partitions are already ready to be sent, the select time would be 0;
            // otherwise if some partition already has some data accumulated but not ready yet,
            // the select time will be the time difference between now and its linger expiry time;
            // otherwise the select time will be the time difference between now and the metadata expiry time;
            pollTimeout = 0;
        }
        sendProduceRequests(batches, now);

        return pollTimeout;

    }

    private boolean maybeSendTransactionalRequest(long now) {
        if (transactionManager != null && transactionManager.hasInflightRequest())
            return true;

        if (transactionManager == null || !transactionManager.hasPendingTransactionalRequests())
            return false;

        TransactionManager.TxnRequestHandler nextRequestHandler = transactionManager.nextRequestHandler();

        if (nextRequestHandler.isEndTxn()) {
            if (transactionManager.isCompletingTransaction() && accumulator.hasUnflushedBatches()) {
                if (!accumulator.flushInProgress())
                    accumulator.beginFlush();
                transactionManager.reenqueue(nextRequestHandler);
                return false;
            } else if (transactionManager.isInErrorState()) {
                nextRequestHandler.fatal(new KafkaException("Cannot commit transaction when there are " +
                        "request errors. Please check your logs for the details of the errors encountered."));
                return false;
            }
        }

        Node targetNode = null;

        while (targetNode == null) {
            try {
                if (nextRequestHandler.needsCoordinator()) {
                    targetNode = transactionManager.coordinator(nextRequestHandler.coordinatorType());
                    if (targetNode == null) {
                        transactionManager.lookupCoordinator(nextRequestHandler);
                        break;
                    }
                    if (!NetworkClientUtils.awaitReady(client, targetNode, time, requestTimeout)) {
                        transactionManager.lookupCoordinator(nextRequestHandler);
                        targetNode = null;
                        break;
                    }
                } else {
                    targetNode = awaitLeastLoadedNodeReady(requestTimeout);
                }
                if (targetNode != null) {
                    if (nextRequestHandler.isRetry()) {
                        time.sleep(retryBackoffMs);
                    }
                    ClientRequest clientRequest = client.newClientRequest(targetNode.idString(), nextRequestHandler.requestBuilder(),
                            now, true, nextRequestHandler);
                    transactionManager.setInFlightRequestCorrelationId(clientRequest.correlationId());
                    client.send(clientRequest, now);
                    return true;
                }
            } catch (IOException e) {
                log.warn("Got an exception when trying to find a node to send a transactional request to. Going to back off and retry", e);
            }
            time.sleep(retryBackoffMs);
            metadata.requestUpdate();
        }

        if (targetNode == null)
            transactionManager.retry(nextRequestHandler);

        return true;
    }

    /**
     * Start closing the sender (won't actually complete until all data is sent out)
     */
    public void initiateClose() {
        // Ensure accumulator is closed first to guarantee that no more appends are accepted after
        // breaking from the sender loop. Otherwise, we may miss some callbacks when shutting down.
        this.accumulator.close();
        this.running = false;
        this.wakeup();
    }

    /**
     * Closes the sender without sending out any pending messages.
     */
    public void forceClose() {
        this.forceClose = true;
        initiateClose();
    }

    private ClientResponse sendAndAwaitInitPidRequest(Node node) throws IOException {
        String nodeId = node.idString();
        InitProducerIdRequest.Builder builder = new InitProducerIdRequest.Builder(null);
        ClientRequest request = client.newClientRequest(nodeId, builder, time.milliseconds(), true, null);
        return NetworkClientUtils.sendAndReceive(client, request, time);
    }

    private Node awaitLeastLoadedNodeReady(long remainingTimeMs) throws IOException {
        Node node = client.leastLoadedNode(time.milliseconds());
        if (NetworkClientUtils.awaitReady(client, node, time, remainingTimeMs)) {
            return node;
        }
        return null;
    }

    private void maybeWaitForPid() {
        // If this is a transactional producer, the PID will be received when recovering transactions in the
        // initTransactions() method of the producer.
        if (transactionManager == null || transactionManager.isTransactional())
            return;

        while (!transactionManager.hasProducerId()) {
            try {
                Node node = awaitLeastLoadedNodeReady(requestTimeout);
                if (node != null) {
                    ClientResponse response = sendAndAwaitInitPidRequest(node);
                    if (response.hasResponse() && (response.responseBody() instanceof InitProducerIdResponse)) {
                        InitProducerIdResponse initProducerIdResponse = (InitProducerIdResponse) response.responseBody();
                        ProducerIdAndEpoch producerIdAndEpoch = new ProducerIdAndEpoch(
                                initProducerIdResponse.producerId(), initProducerIdResponse.epoch());
                        transactionManager.setProducerIdAndEpoch(producerIdAndEpoch);
                    } else {
                        log.error("Received an unexpected response type for an InitProducerIdRequest from {}. " +
                                "We will back off and try again.", node);
                    }
                } else {
                    log.debug("Could not find an available broker to send InitProducerIdRequest to. " +
                            "We will back off and try again.");
                }
            } catch (Exception e) {
                log.warn("Received an exception while trying to get a pid. Will back off and retry.", e);
            }
            log.trace("Retry InitProducerIdRequest in {}ms.", retryBackoffMs);
            time.sleep(retryBackoffMs);
            metadata.requestUpdate();
        }
    }

    /**
     * Handle a produce response
     */
    private void handleProduceResponse(ClientResponse response, Map<TopicPartition, ProducerBatch> batches, long now) {
        int correlationId = response.requestHeader().correlationId();
        if (response.wasDisconnected()) {
            log.trace("Cancelled request {} due to node {} being disconnected", response, response.destination());
            for (ProducerBatch batch : batches.values())
                completeBatch(batch, new ProduceResponse.PartitionResponse(Errors.NETWORK_EXCEPTION), correlationId, now);
        } else if (response.versionMismatch() != null) {
            log.warn("Cancelled request {} due to a version mismatch with node {}",
                    response, response.destination(), response.versionMismatch());
            for (ProducerBatch batch : batches.values())
                completeBatch(batch, new ProduceResponse.PartitionResponse(Errors.INVALID_REQUEST), correlationId, now);
        } else {
            log.trace("Received produce response from node {} with correlation id {}", response.destination(), correlationId);
            // if we have a response, parse it
            if (response.hasResponse()) {
                ProduceResponse produceResponse = (ProduceResponse) response.responseBody();
                for (Map.Entry<TopicPartition, ProduceResponse.PartitionResponse> entry : produceResponse.responses().entrySet()) {
                    TopicPartition tp = entry.getKey();
                    ProduceResponse.PartitionResponse partResp = entry.getValue();
                    ProducerBatch batch = batches.get(tp);
                    completeBatch(batch, partResp, correlationId, now);
                }
                this.sensors.recordLatency(response.destination(), response.requestLatencyMs());
                this.sensors.recordThrottleTime(produceResponse.getThrottleTime());
            } else {
                // this is the acks = 0 case, just complete all requests
                for (ProducerBatch batch : batches.values()) {
                    completeBatch(batch, new ProduceResponse.PartitionResponse(Errors.NONE), correlationId, now);
                }
            }
        }
    }

    /**
     * Complete or retry the given batch of records.
     * 
     * @param batch The record batch
     * @param response The produce response
     * @param correlationId The correlation id for the request
     * @param now The current POSIX timestamp in milliseconds
     */
    private void completeBatch(ProducerBatch batch, ProduceResponse.PartitionResponse response, long correlationId,
                               long now) {
        Errors error = response.error;
        if (error != Errors.NONE) {
            if (canRetry(batch, error)) {
                log.warn("Got error produce response with correlation id {} on topic-partition {}, retrying ({} attempts left). Error: {}",
                        correlationId,
                        batch.topicPartition,
                        this.retries - batch.attempts() - 1,
                        error);
                if (transactionManager == null) {
                    reenqueueBatch(batch, now);
                } else if (transactionManager.pidAndEpoch().producerId == batch.producerId() && transactionManager.pidAndEpoch().epoch == batch.producerEpoch()) {
                    // If idempotence is enabled only retry the request if the current PID is the same as the pid of the batch.
                    log.debug("Retrying batch to topic-partition {}. Sequence number : {}", batch.topicPartition,
                            transactionManager.sequenceNumber(batch.topicPartition));
                    reenqueueBatch(batch, now);
                } else {
                    failBatch(batch, response, new OutOfOrderSequenceException("Attempted to retry sending a " +
                            "batch but the producer id changed from " + batch.producerId() + " to " +
                            transactionManager.pidAndEpoch().producerId + " in the mean time. This batch will be dropped."));
                    this.sensors.recordErrors(batch.topicPartition.topic(), batch.recordCount);
                }
            } else {
                final RuntimeException exception;
                if (error == Errors.TOPIC_AUTHORIZATION_FAILED)
                    exception = new TopicAuthorizationException(batch.topicPartition.topic());
                else
                    exception = error.exception();
                if (error == Errors.OUT_OF_ORDER_SEQUENCE_NUMBER && batch.producerId() == transactionManager.pidAndEpoch().producerId)
                    log.error("The broker received an out of order sequence number for correlation id {}, topic-partition " +
                                    "{} at offset {}. This indicates data loss on the broker, and should be investigated.",
                            correlationId, batch.topicPartition, response.baseOffset);
                // tell the user the result of their request
                failBatch(batch, response, exception);
                this.sensors.recordErrors(batch.topicPartition.topic(), batch.recordCount);
            }
            if (error.exception() instanceof InvalidMetadataException) {
                if (error.exception() instanceof UnknownTopicOrPartitionException)
                    log.warn("Received unknown topic or partition error in produce request on partition {}. The " +
                            "topic/partition may not exist or the user may not have Describe access to it", batch.topicPartition);
                metadata.requestUpdate();
            }

        } else {
            completeBatch(batch, response);

            if (transactionManager != null && transactionManager.pidAndEpoch().producerId == batch.producerId()
                    && transactionManager.pidAndEpoch().epoch == batch.producerEpoch()) {
                transactionManager.incrementSequenceNumber(batch.topicPartition, batch.recordCount);
                log.debug("Incremented sequence number for topic-partition {} to {}", batch.topicPartition,
                        transactionManager.sequenceNumber(batch.topicPartition));
            }
        }

        // Unmute the completed partition.
        if (guaranteeMessageOrder)
            this.accumulator.unmutePartition(batch.topicPartition);
    }

    private void reenqueueBatch(ProducerBatch batch, long currentTimeMs) {
        this.accumulator.reenqueue(batch, currentTimeMs);
        this.sensors.recordRetries(batch.topicPartition.topic(), batch.recordCount);
    }

    private void completeBatch(ProducerBatch batch, ProduceResponse.PartitionResponse response) {
        batch.done(response.baseOffset, response.logAppendTime, null);
        this.accumulator.deallocate(batch);
    }

    private void failBatch(ProducerBatch batch, ProduceResponse.PartitionResponse response, RuntimeException exception) {
        if (transactionManager != null && !transactionManager.isTransactional()
                && batch.producerId() == transactionManager.pidAndEpoch().producerId) {
            // Reset the transaction state since we have hit an irrecoverable exception and cannot make any guarantees
            // about the previously committed message. Note that this will discard the producer id and sequence
            // numbers for all existing partitions.
            transactionManager.resetProducerId();
        }
        batch.done(response.baseOffset, response.logAppendTime, exception);
        this.accumulator.deallocate(batch);
    }

    /**
     * We can retry a send if the error is transient and the number of attempts taken is fewer than the maximum allowed
     */
    private boolean canRetry(ProducerBatch batch, Errors error) {
        return batch.attempts() < this.retries && error.exception() instanceof RetriableException;
    }

    /**
     * Transfer the record batches into a list of produce requests on a per-node basis
     */
    private void sendProduceRequests(Map<Integer, List<ProducerBatch>> collated, long now) {
        for (Map.Entry<Integer, List<ProducerBatch>> entry : collated.entrySet())
            sendProduceRequest(now, entry.getKey(), acks, requestTimeout, entry.getValue());
    }

    /**
     * Create a produce request from the given record batches
     */
    private void sendProduceRequest(long now, int destination, short acks, int timeout, List<ProducerBatch> batches) {
        if (batches.isEmpty())
            return;

        Map<TopicPartition, MemoryRecords> produceRecordsByPartition = new HashMap<>(batches.size());
        final Map<TopicPartition, ProducerBatch> recordsByPartition = new HashMap<>(batches.size());

        // find the minimum magic version used when creating the record sets
        byte minUsedMagic = apiVersions.maxUsableProduceMagic();
        for (ProducerBatch batch : batches) {
            if (batch.magic() < minUsedMagic)
                minUsedMagic = batch.magic();
        }

        for (ProducerBatch batch : batches) {
            TopicPartition tp = batch.topicPartition;
            MemoryRecords records = batch.records();

            // down convert if necessary to the minimum magic used. In general, there can be a delay between the time
            // that the producer starts building the batch and the time that we send the request, and we may have
            // chosen the message format based on out-dated metadata. In the worst case, we optimistically chose to use
            // the new message format, but found that the broker didn't support it, so we need to down-convert on the
            // client before sending. This is intended to handle edge cases around cluster upgrades where brokers may
            // not all support the same message format version. For example, if a partition migrates from a broker
            // which is supporting the new magic version to one which doesn't, then we will need to convert.
            if (!records.hasMatchingMagic(minUsedMagic))
                records = batch.records().downConvert(minUsedMagic);
            produceRecordsByPartition.put(tp, records);
            recordsByPartition.put(tp, batch);
        }

        String transactionalId = null;
        if (transactionManager != null && transactionManager.isTransactional()) {
            transactionalId = transactionManager.transactionalId();
        }
        ProduceRequest.Builder requestBuilder = new ProduceRequest.Builder(minUsedMagic, acks, timeout,
                produceRecordsByPartition, transactionalId);
        RequestCompletionHandler callback = new RequestCompletionHandler() {
            public void onComplete(ClientResponse response) {
                handleProduceResponse(response, recordsByPartition, time.milliseconds());
            }
        };

        String nodeId = Integer.toString(destination);
        ClientRequest clientRequest = client.newClientRequest(nodeId, requestBuilder, now, acks != 0, callback);
        client.send(clientRequest, now);
        log.trace("Sent produce request to {}: {}", nodeId, requestBuilder);
    }

    /**
     * Wake up the selector associated with this send thread
     */
    public void wakeup() {
        this.client.wakeup();
    }

    /**
     * A collection of sensors for the sender
     */
    private class SenderMetrics {

        private final Metrics metrics;
        public final Sensor retrySensor;
        public final Sensor errorSensor;
        public final Sensor queueTimeSensor;
        public final Sensor requestTimeSensor;
        public final Sensor recordsPerRequestSensor;
        public final Sensor batchSizeSensor;
        public final Sensor compressionRateSensor;
        public final Sensor maxRecordSizeSensor;
        public final Sensor produceThrottleTimeSensor;

        public SenderMetrics(Metrics metrics) {
            this.metrics = metrics;
            String metricGrpName = "producer-metrics";

            this.batchSizeSensor = metrics.sensor("batch-size");
            MetricName m = metrics.metricName("batch-size-avg", metricGrpName, "The average number of bytes sent per partition per-request.");
            this.batchSizeSensor.add(m, new Avg());
            m = metrics.metricName("batch-size-max", metricGrpName, "The max number of bytes sent per partition per-request.");
            this.batchSizeSensor.add(m, new Max());

            this.compressionRateSensor = metrics.sensor("compression-rate");
            m = metrics.metricName("compression-rate-avg", metricGrpName, "The average compression rate of record batches.");
            this.compressionRateSensor.add(m, new Avg());

            this.queueTimeSensor = metrics.sensor("queue-time");
            m = metrics.metricName("record-queue-time-avg", metricGrpName, "The average time in ms record batches spent in the record accumulator.");
            this.queueTimeSensor.add(m, new Avg());
            m = metrics.metricName("record-queue-time-max", metricGrpName, "The maximum time in ms record batches spent in the record accumulator.");
            this.queueTimeSensor.add(m, new Max());

            this.requestTimeSensor = metrics.sensor("request-time");
            m = metrics.metricName("request-latency-avg", metricGrpName, "The average request latency in ms");
            this.requestTimeSensor.add(m, new Avg());
            m = metrics.metricName("request-latency-max", metricGrpName, "The maximum request latency in ms");
            this.requestTimeSensor.add(m, new Max());

            this.produceThrottleTimeSensor = metrics.sensor("produce-throttle-time");
            m = metrics.metricName("produce-throttle-time-avg", metricGrpName, "The average throttle time in ms");
            this.produceThrottleTimeSensor.add(m, new Avg());
            m = metrics.metricName("produce-throttle-time-max", metricGrpName, "The maximum throttle time in ms");
            this.produceThrottleTimeSensor.add(m, new Max());

            this.recordsPerRequestSensor = metrics.sensor("records-per-request");
            m = metrics.metricName("record-send-rate", metricGrpName, "The average number of records sent per second.");
            this.recordsPerRequestSensor.add(m, new Rate());
            m = metrics.metricName("records-per-request-avg", metricGrpName, "The average number of records per request.");
            this.recordsPerRequestSensor.add(m, new Avg());

            this.retrySensor = metrics.sensor("record-retries");
            m = metrics.metricName("record-retry-rate", metricGrpName, "The average per-second number of retried record sends");
            this.retrySensor.add(m, new Rate());

            this.errorSensor = metrics.sensor("errors");
            m = metrics.metricName("record-error-rate", metricGrpName, "The average per-second number of record sends that resulted in errors");
            this.errorSensor.add(m, new Rate());

            this.maxRecordSizeSensor = metrics.sensor("record-size-max");
            m = metrics.metricName("record-size-max", metricGrpName, "The maximum record size");
            this.maxRecordSizeSensor.add(m, new Max());
            m = metrics.metricName("record-size-avg", metricGrpName, "The average record size");
            this.maxRecordSizeSensor.add(m, new Avg());

            m = metrics.metricName("requests-in-flight", metricGrpName, "The current number of in-flight requests awaiting a response.");
            this.metrics.addMetric(m, new Measurable() {
                public double measure(MetricConfig config, long now) {
                    return client.inFlightRequestCount();
                }
            });
            m = metrics.metricName("metadata-age", metricGrpName, "The age in seconds of the current producer metadata being used.");
            metrics.addMetric(m, new Measurable() {
                public double measure(MetricConfig config, long now) {
                    return (now - metadata.lastSuccessfulUpdate()) / 1000.0;
                }
            });
        }

        private void maybeRegisterTopicMetrics(String topic) {
            // if one sensor of the metrics has been registered for the topic,
            // then all other sensors should have been registered; and vice versa
            String topicRecordsCountName = "topic." + topic + ".records-per-batch";
            Sensor topicRecordCount = this.metrics.getSensor(topicRecordsCountName);
            if (topicRecordCount == null) {
                Map<String, String> metricTags = Collections.singletonMap("topic", topic);
                String metricGrpName = "producer-topic-metrics";

                topicRecordCount = this.metrics.sensor(topicRecordsCountName);
                MetricName m = this.metrics.metricName("record-send-rate", metricGrpName, metricTags);
                topicRecordCount.add(m, new Rate());

                String topicByteRateName = "topic." + topic + ".bytes";
                Sensor topicByteRate = this.metrics.sensor(topicByteRateName);
                m = this.metrics.metricName("byte-rate", metricGrpName, metricTags);
                topicByteRate.add(m, new Rate());

                String topicCompressionRateName = "topic." + topic + ".compression-rate";
                Sensor topicCompressionRate = this.metrics.sensor(topicCompressionRateName);
                m = this.metrics.metricName("compression-rate", metricGrpName, metricTags);
                topicCompressionRate.add(m, new Avg());

                String topicRetryName = "topic." + topic + ".record-retries";
                Sensor topicRetrySensor = this.metrics.sensor(topicRetryName);
                m = this.metrics.metricName("record-retry-rate", metricGrpName, metricTags);
                topicRetrySensor.add(m, new Rate());

                String topicErrorName = "topic." + topic + ".record-errors";
                Sensor topicErrorSensor = this.metrics.sensor(topicErrorName);
                m = this.metrics.metricName("record-error-rate", metricGrpName, metricTags);
                topicErrorSensor.add(m, new Rate());
            }
        }

        public void updateProduceRequestMetrics(Map<Integer, List<ProducerBatch>> batches) {
            long now = time.milliseconds();
            for (List<ProducerBatch> nodeBatch : batches.values()) {
                int records = 0;
                for (ProducerBatch batch : nodeBatch) {
                    // register all per-topic metrics at once
                    String topic = batch.topicPartition.topic();
                    maybeRegisterTopicMetrics(topic);

                    // per-topic record send rate
                    String topicRecordsCountName = "topic." + topic + ".records-per-batch";
                    Sensor topicRecordCount = Utils.notNull(this.metrics.getSensor(topicRecordsCountName));
                    topicRecordCount.record(batch.recordCount);

                    // per-topic bytes send rate
                    String topicByteRateName = "topic." + topic + ".bytes";
                    Sensor topicByteRate = Utils.notNull(this.metrics.getSensor(topicByteRateName));
                    topicByteRate.record(batch.sizeInBytes());

                    // per-topic compression rate
                    String topicCompressionRateName = "topic." + topic + ".compression-rate";
                    Sensor topicCompressionRate = Utils.notNull(this.metrics.getSensor(topicCompressionRateName));
                    topicCompressionRate.record(batch.compressionRate());

                    // global metrics
                    this.batchSizeSensor.record(batch.sizeInBytes(), now);
                    this.queueTimeSensor.record(batch.queueTimeMs(), now);
                    this.compressionRateSensor.record(batch.compressionRate());
                    this.maxRecordSizeSensor.record(batch.maxRecordSize, now);
                    records += batch.recordCount;
                }
                this.recordsPerRequestSensor.record(records, now);
            }
        }

        public void recordRetries(String topic, int count) {
            long now = time.milliseconds();
            this.retrySensor.record(count, now);
            String topicRetryName = "topic." + topic + ".record-retries";
            Sensor topicRetrySensor = this.metrics.getSensor(topicRetryName);
            if (topicRetrySensor != null)
                topicRetrySensor.record(count, now);
        }

        public void recordErrors(String topic, int count) {
            long now = time.milliseconds();
            this.errorSensor.record(count, now);
            String topicErrorName = "topic." + topic + ".record-errors";
            Sensor topicErrorSensor = this.metrics.getSensor(topicErrorName);
            if (topicErrorSensor != null)
                topicErrorSensor.record(count, now);
        }

        public void recordLatency(String node, long latency) {
            long now = time.milliseconds();
            this.requestTimeSensor.record(latency, now);
            if (!node.isEmpty()) {
                String nodeTimeName = "node-" + node + ".latency";
                Sensor nodeRequestTime = this.metrics.getSensor(nodeTimeName);
                if (nodeRequestTime != null)
                    nodeRequestTime.record(latency, now);
            }
        }

        public void recordThrottleTime(long throttleTimeMs) {
            this.produceThrottleTimeSensor.record(throttleTimeMs, time.milliseconds());
        }

    }

}
