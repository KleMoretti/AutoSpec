package com.autospec.workflow.runtime;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorkflowRecoveryJob {
    private final WorkflowRecoveryService recoveryService;
    private final Clock clock;
    private final Duration leaseTimeout;
    private final AtomicBoolean running = new AtomicBoolean();

    public WorkflowRecoveryJob(
            WorkflowRecoveryService recoveryService,
            Clock clock,
            Duration leaseTimeout
    ) {
        if (leaseTimeout == null || leaseTimeout.isZero() || leaseTimeout.isNegative()) {
            throw new IllegalArgumentException("leaseTimeout must be positive");
        }
        this.recoveryService = recoveryService;
        this.clock = clock;
        this.leaseTimeout = leaseTimeout;
    }

    @Scheduled(
            fixedDelayString = "${autospec.workflow.recovery.fixed-delay:5000}",
            initialDelayString = "${autospec.workflow.recovery.initial-delay:5000}"
    )
    public void scheduledRecover() {
        recoverOnce();
    }

    public boolean recoverOnce() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            recoveryService.recover(LocalDateTime.now(clock), leaseTimeout);
            return true;
        } finally {
            running.set(false);
        }
    }
}
