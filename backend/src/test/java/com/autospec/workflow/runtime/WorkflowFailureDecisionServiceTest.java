package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowFailureDecisionServiceTest {
    private final WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
    private final WorkflowFailureDecisionService service = new WorkflowFailureDecisionService(
            runMapper, new ObjectMapper(), new RetryPolicyEvaluator()
    );
    private final LocalDateTime failedAt = LocalDateTime.of(2026, 7, 13, 12, 0);

    @Test
    void resolvesRetryPolicyFromFrozenWorkflowSnapshot() {
        WorkflowRun run = runWithSnapshot("""
                {
                  "nodes":[{
                    "node_id":"backend",
                    "retry_policy":{
                      "max_attempts":3,
                      "retryable_errors":["MODEL_TIMEOUT"],
                      "initial_delay_ms":1000,
                      "max_delay_ms":5000,
                      "multiplier":2.0
                    },
                    "fallback":{"enabled":false}
                  }]
                }
                """);
        when(runMapper.selectById(7L)).thenReturn(run);

        RetryPolicyEvaluator.Decision decision = service.decide(
                node(1), "MODEL_TIMEOUT", failedAt
        );

        assertThat(decision.action()).isEqualTo(RetryPolicyEvaluator.Action.RETRY);
        assertThat(decision.nextRetryAt()).isEqualTo(failedAt.plusSeconds(1));
    }

    @Test
    void resolvesFallbackHandlerAfterAttemptsAreExhausted() {
        WorkflowRun run = runWithSnapshot("""
                {
                  "nodes":[{
                    "node_id":"backend",
                    "retry_policy":{"max_attempts":1,"retryable_errors":["MODEL_TIMEOUT"]},
                    "fallback":{"enabled":true,"handler":"backend-safe-v1"}
                  }]
                }
                """);
        when(runMapper.selectById(7L)).thenReturn(run);

        RetryPolicyEvaluator.Decision decision = service.decide(
                node(1), "MODEL_TIMEOUT", failedAt
        );

        assertThat(decision.action()).isEqualTo(RetryPolicyEvaluator.Action.FALLBACK);
        assertThat(decision.handlerKey()).isEqualTo("backend-safe-v1");
    }

    @Test
    void failsClosedWhenFrozenPolicyCannotBeResolved() {
        when(runMapper.selectById(7L)).thenReturn(null);

        RetryPolicyEvaluator.Decision decision = service.decide(
                node(1), "MODEL_TIMEOUT", failedAt
        );

        assertThat(decision.action()).isEqualTo(RetryPolicyEvaluator.Action.FAIL);
    }

    private WorkflowRun runWithSnapshot(String snapshot) {
        WorkflowRun run = new WorkflowRun();
        run.setId(7L);
        run.setWorkflowSnapshotJson(snapshot);
        return run;
    }

    private WorkflowNodeRun node(int attempt) {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setWorkflowRunId(7L);
        node.setNodeId("backend");
        node.setAttempt(attempt);
        return node;
    }
}
