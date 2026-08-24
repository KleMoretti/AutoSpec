package com.autospec.workflow.transport;

import java.time.Duration;
import java.util.List;

public interface WorkflowEventStreamClient {
    void ensureGroup(String stream, String group);

    default List<WorkflowStreamEventMessage> claimStale(
            String stream,
            String group,
            String consumer,
            Duration minimumIdleTime,
            int count
    ) {
        return List.of();
    }

    List<WorkflowStreamEventMessage> read(
            String stream, String group, String consumer, int count
    );

    void acknowledge(String stream, String group, String messageId);
}
