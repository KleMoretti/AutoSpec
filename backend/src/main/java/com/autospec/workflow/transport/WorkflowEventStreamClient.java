package com.autospec.workflow.transport;

import java.util.List;

public interface WorkflowEventStreamClient {
    void ensureGroup(String stream, String group);

    List<WorkflowStreamEventMessage> read(
            String stream, String group, String consumer, int count
    );

    void acknowledge(String stream, String group, String messageId);
}
