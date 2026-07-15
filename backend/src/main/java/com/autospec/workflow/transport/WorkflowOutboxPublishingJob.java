package com.autospec.workflow.transport;

import org.springframework.scheduling.annotation.Scheduled;

public class WorkflowOutboxPublishingJob {
    private final WorkflowOutboxPublisher publisher;
    private final int batchSize;

    public WorkflowOutboxPublishingJob(WorkflowOutboxPublisher publisher, int batchSize) {
        this.publisher = publisher;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            fixedDelayString = "${autospec.workflow.outbox.fixed-delay:500}",
            initialDelayString = "${autospec.workflow.outbox.initial-delay:1000}"
    )
    public void publish() {
        publisher.publishPending(batchSize);
    }
}
