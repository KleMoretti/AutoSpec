package com.autospec.workflow.transport;

import java.time.LocalDateTime;

@FunctionalInterface
public interface WorkflowEventDeliveryAttemptStore {
    int recordAttempt(String streamMessageId, String consumerGroup, LocalDateTime now);

    static WorkflowEventDeliveryAttemptStore none() {
        return (messageId, consumerGroup, now) -> 1;
    }
}
