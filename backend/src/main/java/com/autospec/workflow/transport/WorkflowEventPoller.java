package com.autospec.workflow.transport;

import java.util.List;

public class WorkflowEventPoller {
    public static final String EVENT_STREAM = "autospec.workflow.events";
    public static final String CONTROL_GROUP = "autospec-control-plane";

    private final WorkflowEventStreamClient streamClient;
    private final WorkflowEventMessageHandler messageHandler;
    private final String consumerName;
    private final int batchSize;

    public WorkflowEventPoller(
            WorkflowEventStreamClient streamClient,
            WorkflowEventMessageHandler messageHandler,
            String consumerName
    ) {
        this(streamClient, messageHandler, consumerName, 10);
    }

    public WorkflowEventPoller(
            WorkflowEventStreamClient streamClient,
            WorkflowEventMessageHandler messageHandler,
            String consumerName,
            int batchSize
    ) {
        this.streamClient = streamClient;
        this.messageHandler = messageHandler;
        this.consumerName = consumerName;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    public int pollOnce() {
        streamClient.ensureGroup(EVENT_STREAM, CONTROL_GROUP);
        List<WorkflowStreamEventMessage> messages = streamClient.read(
                EVENT_STREAM, CONTROL_GROUP, consumerName, batchSize
        );
        int processed = 0;
        for (WorkflowStreamEventMessage message : messages) {
            messageHandler.handle(message.payloadJson());
            streamClient.acknowledge(EVENT_STREAM, CONTROL_GROUP, message.messageId());
            processed++;
        }
        return processed;
    }
}
