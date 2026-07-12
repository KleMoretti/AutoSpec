package com.autospec.workflow.transport;

import org.springframework.scheduling.annotation.Scheduled;

public class WorkflowEventPollingJob {
    private final WorkflowEventPoller poller;

    public WorkflowEventPollingJob(WorkflowEventPoller poller) {
        this.poller = poller;
    }

    @Scheduled(
            fixedDelayString = "${autospec.workflow.events.polling.fixed-delay:1000}",
            initialDelayString = "${autospec.workflow.events.polling.initial-delay:1000}"
    )
    public void poll() {
        poller.pollOnce();
    }
}
