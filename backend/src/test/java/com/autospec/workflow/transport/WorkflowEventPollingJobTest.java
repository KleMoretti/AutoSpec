package com.autospec.workflow.transport;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowEventPollingJobTest {

    @Test
    void keepsScheduledLoopAliveWhenOnePollingIterationFails() {
        WorkflowEventPoller poller = mock(WorkflowEventPoller.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(poller)
                .pollOnce();

        new WorkflowEventPollingJob(poller).poll();

        verify(poller).pollOnce();
    }
}
