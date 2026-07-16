package com.autospec.workflow.transport;

import java.time.Duration;
import java.util.List;

public class WorkflowEventPoller {
    public static final String EVENT_STREAM = "autospec.workflow.events";
    public static final String CONTROL_GROUP = "autospec-control-plane";
    private static final Duration DEFAULT_CLAIM_MIN_IDLE = Duration.ofSeconds(30);

    private final WorkflowEventStreamClient streamClient;
    private final WorkflowEventMessageHandler messageHandler;
    private final String consumerName;
    private final int batchSize;
    private final Duration claimMinIdle;
    private final WorkflowTransportMetrics metrics;

    public WorkflowEventPoller(
            WorkflowEventStreamClient streamClient,
            WorkflowEventMessageHandler messageHandler,
            String consumerName
    ) {
        this(
                streamClient,
                messageHandler,
                consumerName,
                10,
                DEFAULT_CLAIM_MIN_IDLE,
                WorkflowTransportMetrics.isolated()
        );
    }

    public WorkflowEventPoller(
            WorkflowEventStreamClient streamClient,
            WorkflowEventMessageHandler messageHandler,
            String consumerName,
            int batchSize
    ) {
        this(
                streamClient,
                messageHandler,
                consumerName,
                batchSize,
                DEFAULT_CLAIM_MIN_IDLE,
                WorkflowTransportMetrics.isolated()
        );
    }

    public WorkflowEventPoller(
            WorkflowEventStreamClient streamClient,
            WorkflowEventMessageHandler messageHandler,
            String consumerName,
            int batchSize,
            Duration claimMinIdle
    ) {
        this(
                streamClient,
                messageHandler,
                consumerName,
                batchSize,
                claimMinIdle,
                WorkflowTransportMetrics.isolated()
        );
    }

    public WorkflowEventPoller(
            WorkflowEventStreamClient streamClient,
            WorkflowEventMessageHandler messageHandler,
            String consumerName,
            int batchSize,
            Duration claimMinIdle,
            WorkflowTransportMetrics metrics
    ) {
        this.streamClient = streamClient;
        this.messageHandler = messageHandler;
        this.consumerName = consumerName;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        if (claimMinIdle.isNegative()) {
            throw new IllegalArgumentException("claimMinIdle must not be negative");
        }
        this.claimMinIdle = claimMinIdle;
        this.metrics = metrics;
    }

    public int pollOnce() {
        streamClient.ensureGroup(EVENT_STREAM, CONTROL_GROUP);
        List<WorkflowStreamEventMessage> reclaimed = streamClient.claimStale(
                EVENT_STREAM, CONTROL_GROUP, consumerName, claimMinIdle, batchSize
        );
        metrics.recordReclaimedEvents(reclaimed.size());
        List<WorkflowStreamEventMessage> fresh = streamClient.read(
                EVENT_STREAM, CONTROL_GROUP, consumerName, batchSize
        );
        metrics.recordFreshEvents(fresh.size());
        int processed = process(reclaimed);
        return processed + process(fresh);
    }

    private int process(List<WorkflowStreamEventMessage> messages) {
        int processed = 0;
        for (WorkflowStreamEventMessage message : messages) {
            try {
                messageHandler.handle(message.payloadJson());
            } catch (RuntimeException | Error failure) {
                metrics.recordEventHandlerFailure();
                throw failure;
            }
            streamClient.acknowledge(EVENT_STREAM, CONTROL_GROUP, message.messageId());
            metrics.recordAcknowledgedEvent();
            processed++;
        }
        return processed;
    }
}
