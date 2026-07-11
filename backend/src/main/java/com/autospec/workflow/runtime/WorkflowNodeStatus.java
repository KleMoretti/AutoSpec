package com.autospec.workflow.runtime;

import java.util.EnumSet;
import java.util.Set;

public enum WorkflowNodeStatus {
    PENDING,
    READY,
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    FALLBACK_READY,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    STALE,
    SKIPPED,
    CANCELLED,
    ORPHANED;

    public boolean canTransitionTo(WorkflowNodeStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<WorkflowNodeStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(READY, SKIPPED, CANCELLED);
            case READY -> EnumSet.of(QUEUED, WAITING_APPROVAL, CANCELLED);
            case QUEUED -> EnumSet.of(RUNNING, CANCELLED, ORPHANED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, RETRY_WAIT, FALLBACK_READY,
                    WAITING_APPROVAL, CANCELLED, ORPHANED);
            case RETRY_WAIT -> EnumSet.of(READY, CANCELLED);
            case FALLBACK_READY -> EnumSet.of(QUEUED, CANCELLED);
            case WAITING_APPROVAL -> EnumSet.of(SUCCEEDED, PENDING, CANCELLED);
            case SUCCEEDED -> EnumSet.of(STALE);
            case STALE -> EnumSet.of(PENDING, CANCELLED);
            case ORPHANED -> EnumSet.of(RETRY_WAIT, FAILED, CANCELLED);
            case FAILED, SKIPPED, CANCELLED -> EnumSet.noneOf(WorkflowNodeStatus.class);
        };
    }

    public boolean isDependencyFailure() {
        return this == FAILED || this == CANCELLED;
    }
}
