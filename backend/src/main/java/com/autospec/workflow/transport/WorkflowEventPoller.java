package com.autospec.workflow.transport;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public class WorkflowEventPoller {
    public static final String EVENT_STREAM = "autospec.workflow.events";
    public static final String CONTROL_GROUP = "autospec-control-plane";
    private static final Duration DEFAULT_CLAIM_MIN_IDLE = Duration.ofSeconds(30);
    private static final int MAX_DELIVERY_ATTEMPTS = 3;

    private final WorkflowEventStreamClient streamClient;
    private final WorkflowEventMessageHandler messageHandler;
    private final String consumerName;
    private final int batchSize;
    private final Duration claimMinIdle;
    private final WorkflowTransportMetrics metrics;
    private final WorkflowEventDeadLetterSink deadLetterSink;
    private final WorkflowEventDeliveryAttemptStore deliveryAttemptStore;

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
        this(
                streamClient,
                messageHandler,
                consumerName,
                batchSize,
                claimMinIdle,
                metrics,
                WorkflowEventDeadLetterSink.none(),
                WorkflowEventDeliveryAttemptStore.none()
        );
    }

    public WorkflowEventPoller(
            WorkflowEventStreamClient streamClient,
            WorkflowEventMessageHandler messageHandler,
            String consumerName,
            int batchSize,
            Duration claimMinIdle,
            WorkflowTransportMetrics metrics,
            WorkflowEventDeadLetterSink deadLetterSink
    ) {
        this(
                streamClient, messageHandler, consumerName, batchSize, claimMinIdle,
                metrics, deadLetterSink, WorkflowEventDeliveryAttemptStore.none()
        );
    }

    public WorkflowEventPoller(
            WorkflowEventStreamClient streamClient,
            WorkflowEventMessageHandler messageHandler,
            String consumerName,
            int batchSize,
            Duration claimMinIdle,
            WorkflowTransportMetrics metrics,
            WorkflowEventDeadLetterSink deadLetterSink,
            WorkflowEventDeliveryAttemptStore deliveryAttemptStore
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
        this.deadLetterSink = deadLetterSink;
        this.deliveryAttemptStore = deliveryAttemptStore;
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
            int deliveryCount = deliveryAttemptStore.recordAttempt(
                    message.messageId(), CONTROL_GROUP, LocalDateTime.now()
            );
            try {
                messageHandler.handle(message.payloadJson());
            } catch (InvalidWorkflowEventException invalidEvent) {
                try {
                    deadLetterSink.quarantine(message, invalidEvent);
                } catch (RuntimeException | Error quarantineFailure) {
                    metrics.recordEventHandlerFailure();
                    throw quarantineFailure;
                }
                streamClient.acknowledge(EVENT_STREAM, CONTROL_GROUP, message.messageId());
                metrics.recordAcknowledgedEvent();
                metrics.recordEventDeadLetter();
                processed++;
                continue;
            } catch (RuntimeException | Error failure) {
                if (failure instanceof RuntimeException runtimeFailure
                        && deliveryCount >= MAX_DELIVERY_ATTEMPTS) {
                    deadLetterSink.quarantinePoison(message, runtimeFailure);
                    streamClient.acknowledge(EVENT_STREAM, CONTROL_GROUP, message.messageId());
                    metrics.recordAcknowledgedEvent();
                    metrics.recordEventDeadLetter();
                    processed++;
                    continue;
                }
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
