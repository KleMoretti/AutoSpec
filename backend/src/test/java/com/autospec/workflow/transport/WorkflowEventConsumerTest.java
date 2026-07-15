package com.autospec.workflow.transport;

import com.autospec.entity.ProcessedWorkflowEvent;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.workflow.runtime.RetryPolicyEvaluator;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEventConsumerTest {

    @Test
    void ignoresAnAlreadyProcessedEventId() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        when(processedMapper.selectCount(any())).thenReturn(1L);
        WorkflowEventConsumer consumer = consumer(processedMapper, nodeMapper, trigger);

        WorkflowEventOutcome outcome = consumer.consume(successPayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.DUPLICATE);
        verify(nodeMapper, never()).update(any(), any());
        verify(trigger, never()).reconcile(any(Long.class));
    }

    @Test
    void acceptsSuccessForCurrentExecutionAndTriggersReconciliation() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        when(processedMapper.selectCount(any())).thenReturn(0L);
        when(processedMapper.insert(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(1);
        WorkflowEventConsumer consumer = consumer(processedMapper, nodeMapper, trigger);

        WorkflowEventOutcome outcome = consumer.consume(successPayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        verify(processedMapper).insert(any(ProcessedWorkflowEvent.class));
        verify(trigger).reconcile(7L);
    }

    @Test
    void recordsButDoesNotApplyLateExecutionEvent() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        when(processedMapper.selectCount(any())).thenReturn(0L);
        when(processedMapper.insert(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(0);
        WorkflowEventConsumer consumer = consumer(processedMapper, nodeMapper, trigger);

        WorkflowEventOutcome outcome = consumer.consume(successPayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.STALE);
        verify(trigger, never()).reconcile(any(Long.class));
    }

    @Test
    void heartbeatUpdatesLeaseWithoutTriggeringReconciliation() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        when(processedMapper.selectCount(any())).thenReturn(0L);
        when(processedMapper.insert(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(1);
        WorkflowEventConsumer consumer = consumer(processedMapper, nodeMapper, trigger);

        WorkflowEventOutcome outcome = consumer.consume(heartbeatPayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        verify(trigger, never()).reconcile(any(Long.class));
    }

    @Test
    void retryableFailurePersistsRetryWaitAndNextRetryTime() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        WorkflowFailureDecisionService failureDecisions = mock(WorkflowFailureDecisionService.class);
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId(11L);
        nodeRun.setAttempt(1);
        LocalDateTime retryAt = LocalDateTime.of(2026, 7, 13, 12, 0, 1);
        when(processedMapper.selectCount(any())).thenReturn(0L);
        when(processedMapper.insert(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.selectById(11L)).thenReturn(nodeRun);
        when(nodeMapper.update(any(), any())).thenReturn(1);
        when(failureDecisions.decide(eq(nodeRun), eq("MODEL_TIMEOUT"), any()))
                .thenReturn(new RetryPolicyEvaluator.Decision(
                        RetryPolicyEvaluator.Action.RETRY, retryAt, null
                ));
        WorkflowEventConsumer consumer = new WorkflowEventConsumer(
                processedMapper, nodeMapper, trigger, failureDecisions, new ObjectMapper()
        );

        WorkflowEventOutcome outcome = consumer.consume(failurePayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        ArgumentCaptor<Wrapper<WorkflowNodeRun>> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(nodeMapper).update(eq(null), update.capture());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WorkflowNodeRun> values =
                (com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WorkflowNodeRun>) update.getValue();
        assertThat(values.getParamNameValuePairs().values())
                .contains("RETRY_WAIT", retryAt, "MODEL_TIMEOUT");
        verify(trigger).reconcile(7L);
    }

    private WorkflowEventConsumer consumer(
            ProcessedWorkflowEventMapper processedMapper,
            WorkflowNodeRunMapper nodeMapper,
            WorkflowRunReconciliationTrigger trigger
    ) {
        WorkflowFailureDecisionService failureDecisions = mock(WorkflowFailureDecisionService.class);
        return new WorkflowEventConsumer(
                processedMapper, nodeMapper, trigger, failureDecisions, new ObjectMapper()
        );
    }

    private String successPayload() {
        return """
                {
                  "event_id":"7:fixture:1:1:succeeded",
                  "source_event_id":"command-1",
                  "event_type":"NODE_SUCCEEDED",
                  "workflow_run_id":7,
                  "node_run_id":11,
                  "node_id":"fixture",
                  "revision":1,
                  "attempt":1,
                  "execution_id":"7:fixture:1:1",
                  "duration_ms":12,
                  "output_payload":{"doubled":6}
                }
                """;
    }

    private String heartbeatPayload() {
        return successPayload()
                .replace("7:fixture:1:1:succeeded", "7:fixture:1:1:heartbeat:1")
                .replace("NODE_SUCCEEDED", "NODE_HEARTBEAT");
    }

    private String failurePayload() {
        return successPayload()
                .replace("7:fixture:1:1:succeeded", "7:fixture:1:1:failed:MODEL_TIMEOUT")
                .replace("NODE_SUCCEEDED", "NODE_FAILED")
                .replace("\"output_payload\":{\"doubled\":6}",
                        "\"error_code\":\"MODEL_TIMEOUT\",\"error_message\":\"timed out\"");
    }
}
