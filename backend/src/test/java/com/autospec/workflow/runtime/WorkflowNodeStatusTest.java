package com.autospec.workflow.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowNodeStatusTest {

    @Test
    void permitsOnlyDeclaredStateTransitions() {
        assertThat(WorkflowNodeStatus.PENDING.canTransitionTo(WorkflowNodeStatus.READY)).isTrue();
        assertThat(WorkflowNodeStatus.READY.canTransitionTo(WorkflowNodeStatus.QUEUED)).isTrue();
        assertThat(WorkflowNodeStatus.QUEUED.canTransitionTo(WorkflowNodeStatus.RUNNING)).isTrue();
        assertThat(WorkflowNodeStatus.RUNNING.canTransitionTo(WorkflowNodeStatus.SUCCEEDED)).isTrue();
        assertThat(WorkflowNodeStatus.RUNNING.canTransitionTo(WorkflowNodeStatus.RETRY_WAIT)).isTrue();
        assertThat(WorkflowNodeStatus.SUCCEEDED.canTransitionTo(WorkflowNodeStatus.RUNNING)).isFalse();
        assertThat(WorkflowNodeStatus.FAILED.canTransitionTo(WorkflowNodeStatus.SUCCEEDED)).isFalse();
    }
}
