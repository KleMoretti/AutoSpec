package com.autospec.workflow.transport;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowOutboxPublishingJobTest {
    @Test
    void scheduledTickPublishesConfiguredBatch() {
        WorkflowOutboxPublisher publisher = mock(WorkflowOutboxPublisher.class);

        new WorkflowOutboxPublishingJob(publisher, 25).publish();

        verify(publisher).publishPending(25);
    }

    @Test
    void clampsUnsafeBatchSizes() {
        WorkflowOutboxPublisher publisher = mock(WorkflowOutboxPublisher.class);

        new WorkflowOutboxPublishingJob(publisher, 1000).publish();

        verify(publisher).publishPending(100);
    }
}
