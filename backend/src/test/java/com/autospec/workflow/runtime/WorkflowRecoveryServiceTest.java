package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRecoveryServiceTest {

    @Test
    void orphansStaleAttemptAndCreatesReplacementAttempt() {
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowOutboxMapper outboxMapper = mock(WorkflowOutboxMapper.class);
        WorkflowNodeRun stale = node(11L, "RUNNING", 1, "7:backend:1:1");
        when(nodeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(stale), List.of());
        when(nodeMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        WorkflowRecoveryService service = service(nodeMapper, outboxMapper);

        WorkflowRecoveryService.RecoveryResult result = service.recover(
                LocalDateTime.of(2026, 7, 12, 12, 0), Duration.ofSeconds(30)
        );

        ArgumentCaptor<WorkflowNodeRun> inserted = ArgumentCaptor.forClass(WorkflowNodeRun.class);
        verify(nodeMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(inserted.getValue().getAttempt()).isEqualTo(2);
        assertThat(inserted.getValue().getExecutionId()).isEqualTo("7:backend:1:2");
        assertThat(result.orphanedAttempts()).isEqualTo(1);
        assertThat(result.replacementAttempts()).isEqualTo(1);
    }

    @Test
    void compensatesQueuedNodeWhoseCommandWasNeverWritten() throws Exception {
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowOutboxMapper outboxMapper = mock(WorkflowOutboxMapper.class);
        WorkflowNodeRun queued = node(12L, "QUEUED", 1, "7:frontend:1:1");
        when(nodeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(), List.of(), List.of(queued));
        when(nodeMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(outboxMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        WorkflowRecoveryService service = service(nodeMapper, outboxMapper);

        WorkflowRecoveryService.RecoveryResult result = service.recover(
                LocalDateTime.of(2026, 7, 12, 12, 0), Duration.ofSeconds(30)
        );

        ArgumentCaptor<WorkflowOutbox> inserted = ArgumentCaptor.forClass(WorkflowOutbox.class);
        verify(outboxMapper).insert(inserted.capture());
        WorkflowOutbox outbox = inserted.getValue();
        assertThat(outbox.getEventType()).isEqualTo("EXECUTE_NODE");
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        assertThat(new ObjectMapper().readTree(outbox.getPayloadJson()).get("node_run_id").asLong())
                .isEqualTo(12L);
        assertThat(result.compensatedCommands()).isEqualTo(1);
    }

    @Test
    void doesNotCreateReplacementWhenAnotherControlPlaneWinsOrphanRace() {
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowOutboxMapper outboxMapper = mock(WorkflowOutboxMapper.class);
        WorkflowNodeRun stale = node(11L, "RUNNING", 1, "7:backend:1:1");
        when(nodeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(stale), List.of());
        when(nodeMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        WorkflowRecoveryService.RecoveryResult result = service(nodeMapper, outboxMapper)
                .recover(LocalDateTime.of(2026, 7, 12, 12, 0), Duration.ofSeconds(30));

        verify(nodeMapper, never()).insert(any(WorkflowNodeRun.class));
        assertThat(result.orphanedAttempts()).isZero();
        assertThat(result.replacementAttempts()).isZero();
    }

    @Test
    void doesNotCompensateWhenExecuteCommandAlreadyExists() {
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowOutboxMapper outboxMapper = mock(WorkflowOutboxMapper.class);
        WorkflowNodeRun queued = node(12L, "QUEUED", 1, "7:frontend:1:1");
        when(nodeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(), List.of(), List.of(queued));
        when(outboxMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        WorkflowRecoveryService.RecoveryResult result = service(nodeMapper, outboxMapper)
                .recover(LocalDateTime.of(2026, 7, 12, 12, 0), Duration.ofSeconds(30));

        verify(outboxMapper, never()).insert(any(WorkflowOutbox.class));
        assertThat(result.compensatedCommands()).isZero();
    }

    @Test
    void doesNotCompensateWhenAnotherControlPlaneClaimsQueuedNode() {
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowOutboxMapper outboxMapper = mock(WorkflowOutboxMapper.class);
        WorkflowNodeRun queued = node(12L, "QUEUED", 1, "7:frontend:1:1");
        when(nodeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(), List.of(), List.of(queued));
        when(outboxMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(nodeMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        WorkflowRecoveryService.RecoveryResult result = service(nodeMapper, outboxMapper)
                .recover(LocalDateTime.of(2026, 7, 12, 12, 0), Duration.ofSeconds(30));

        verify(outboxMapper, never()).insert(any(WorkflowOutbox.class));
        assertThat(result.compensatedCommands()).isZero();
    }

    @Test
    void promotesDueRetryWaitIntoNextAttempt() {
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowOutboxMapper outboxMapper = mock(WorkflowOutboxMapper.class);
        WorkflowNodeRun retryWait = node(13L, "RETRY_WAIT", 1, "7:backend:1:1");
        retryWait.setNodeId("backend");
        retryWait.setNextRetryAt(LocalDateTime.of(2026, 7, 13, 11, 59));
        when(nodeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(), List.of(retryWait), List.of());
        when(nodeMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);

        WorkflowRecoveryService.RecoveryResult result = service(nodeMapper, outboxMapper, trigger)
                .recover(LocalDateTime.of(2026, 7, 13, 12, 0), Duration.ofSeconds(30));

        ArgumentCaptor<WorkflowNodeRun> inserted = ArgumentCaptor.forClass(WorkflowNodeRun.class);
        verify(nodeMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getAttempt()).isEqualTo(2);
        assertThat(inserted.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(inserted.getValue().getHandlerKey()).isEqualTo("handler");
        assertThat(result.replacementAttempts()).isEqualTo(1);
        verify(trigger).reconcile(7L);
    }

    @Test
    void promotedFallbackAttemptKeepsSelectedFallbackHandler() {
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowOutboxMapper outboxMapper = mock(WorkflowOutboxMapper.class);
        WorkflowNodeRun fallback = node(14L, "FALLBACK_READY", 2, "7:backend:1:2");
        fallback.setNodeId("backend");
        fallback.setHandlerKey("backend-safe-v1");
        fallback.setNextRetryAt(LocalDateTime.of(2026, 7, 13, 12, 0));
        when(nodeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(), List.of(fallback), List.of());
        when(nodeMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        service(nodeMapper, outboxMapper)
                .recover(LocalDateTime.of(2026, 7, 13, 12, 0), Duration.ofSeconds(30));

        ArgumentCaptor<WorkflowNodeRun> inserted = ArgumentCaptor.forClass(WorkflowNodeRun.class);
        verify(nodeMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getAttempt()).isEqualTo(3);
        assertThat(inserted.getValue().getHandlerKey()).isEqualTo("backend-safe-v1");
    }

    @Test
    void doesNotPromoteDueRetryWhenAnotherControlPlaneWinsClaim() {
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowOutboxMapper outboxMapper = mock(WorkflowOutboxMapper.class);
        WorkflowNodeRun retryWait = node(13L, "RETRY_WAIT", 1, "7:backend:1:1");
        retryWait.setNextRetryAt(LocalDateTime.of(2026, 7, 13, 11, 59));
        when(nodeMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(), List.of(retryWait), List.of());
        when(nodeMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        WorkflowRecoveryService.RecoveryResult result = service(nodeMapper, outboxMapper)
                .recover(LocalDateTime.of(2026, 7, 13, 12, 0), Duration.ofSeconds(30));

        verify(nodeMapper, never()).insert(any(WorkflowNodeRun.class));
        assertThat(result.replacementAttempts()).isZero();
    }

    private WorkflowRecoveryService service(
            WorkflowNodeRunMapper nodeMapper,
            WorkflowOutboxMapper outboxMapper
    ) {
        return service(nodeMapper, outboxMapper, mock(WorkflowRunReconciliationTrigger.class));
    }

    private WorkflowRecoveryService service(
            WorkflowNodeRunMapper nodeMapper,
            WorkflowOutboxMapper outboxMapper,
            WorkflowRunReconciliationTrigger trigger
    ) {
        return new WorkflowRecoveryService(nodeMapper, outboxMapper, new ObjectMapper(), trigger);
    }

    private WorkflowNodeRun node(Long id, String status, int attempt, String executionId) {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setId(id);
        node.setWorkflowRunId(7L);
        node.setNodeId(id == 11L ? "backend" : "frontend");
        node.setRevision(1);
        node.setAttempt(attempt);
        node.setExecutionId(executionId);
        node.setStatus(status);
        node.setHandlerKey("handler");
        node.setHandlerVersion("v1");
        node.setInputJson("{}");
        node.setLockVersion(2);
        return node;
    }
}
