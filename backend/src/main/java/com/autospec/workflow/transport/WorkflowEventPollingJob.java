package com.autospec.workflow.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class WorkflowEventPollingJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowEventPollingJob.class);

    private final WorkflowEventPoller poller;

    public WorkflowEventPollingJob(WorkflowEventPoller poller) {
        this.poller = poller;
    }

    @Scheduled(
            fixedDelayString = "${autospec.workflow.events.polling.fixed-delay:1000}",
            initialDelayString = "${autospec.workflow.events.polling.initial-delay:1000}"
    )
    public void poll() {
        try {
            poller.pollOnce();
        } catch (RuntimeException failure) {
            // Keep the scheduled loop alive. The Redis consumer group retains
            // the unacknowledged message for a later XAUTOCLAIM retry.
            LOGGER.error(
                    "Workflow event polling iteration failed; pending messages remain eligible for retry",
                    failure
            );
        }
    }
}
