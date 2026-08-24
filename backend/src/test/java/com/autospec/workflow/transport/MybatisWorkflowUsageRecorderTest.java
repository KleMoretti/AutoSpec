package com.autospec.workflow.transport;

import com.autospec.entity.ModelInvocation;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MybatisWorkflowUsageRecorderTest {
    @Mock
    private ModelInvocationMapper invocationMapper;
    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private WorkflowNodeRunMapper nodeRunMapper;

    private MybatisWorkflowUsageRecorder recorder;
    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        recorder = new MybatisWorkflowUsageRecorder(invocationMapper, runMapper, nodeRunMapper);
        run = new WorkflowRun();
        run.setId(7L);
        run.setProjectId(3L);
        run.setStatus("RUNNING");
        run.setMaxTokens(1_000L);
        run.setMaxCost(new BigDecimal("10.00"));
        run.setMaxModelCalls(10);
        run.setMaxWallTimeMs(60_000L);
        run.setConsumedTokens(0L);
        run.setConsumedCost(BigDecimal.ZERO);
        run.setModelCallCount(0);
        run.setStartedAt(LocalDateTime.now());

        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setId(11L);
        node.setWorkflowRunId(7L);
        node.setExecutionId("7:pm:1:1");
        node.setStatus("RUNNING");
        when(runMapper.selectById(7L)).thenReturn(run);
        when(nodeRunMapper.selectById(11L)).thenReturn(node);
    }

    @Test
    void recordsInvocationAndAtomicallyReservesAvailableBudget() {
        when(runMapper.reserveModelUsage(7L, 120L, new BigDecimal("0.25"), 1))
                .thenReturn(1);

        WorkflowUsageRecorder.UsageDecision decision = recorder.record(event(100, 20, 1, "0.25"));

        assertThat(decision).isEqualTo(WorkflowUsageRecorder.UsageDecision.ALLOWED);
        ArgumentCaptor<ModelInvocation> invocation = ArgumentCaptor.forClass(ModelInvocation.class);
        verify(invocationMapper).insert(invocation.capture());
        assertThat(invocation.getValue().getWorkflowRunId()).isEqualTo(7L);
        assertThat(invocation.getValue().getWorkflowNodeRunId()).isEqualTo(11L);
        assertThat(invocation.getValue().getProviderKey()).isEqualTo("openai-compatible");
        assertThat(invocation.getValue().getPromptKey()).isEqualTo("ProductManagerAgent_v1");
        assertThat(invocation.getValue().getRouteKey()).isEqualTo("deep");
        assertThat(invocation.getValue().getRouteReason()).contains("profile=DEEP");
        assertThat(invocation.getValue().getFallbackUsed()).isFalse();
        assertThat(invocation.getValue().getInputTokens()).isEqualTo(100);
        assertThat(invocation.getValue().getOutputTokens()).isEqualTo(20);
        assertThat(invocation.getValue().getCacheTokens()).isEqualTo(15);
        verify(runMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void failsRunAndCancelsActiveNodesWhenBudgetCannotBeReserved() {
        when(runMapper.reserveModelUsage(7L, 1_100L, new BigDecimal("0.25"), 1))
                .thenReturn(0);

        WorkflowUsageRecorder.UsageDecision decision = recorder.record(event(1_000, 100, 1, "0.25"));

        assertThat(decision).isEqualTo(WorkflowUsageRecorder.UsageDecision.BUDGET_EXCEEDED);
        verify(runMapper).update(isNull(), any(Wrapper.class));
        verify(nodeRunMapper).update(isNull(), any(Wrapper.class));
    }

    private WorkflowExecutionEvent event(
            int inputTokens,
            int outputTokens,
            int calls,
            String estimatedCost
    ) {
        return new WorkflowExecutionEvent(
                "event-1",
                "command-1",
                "NODE_SUCCEEDED",
                7L,
                11L,
                "pm",
                1,
                1,
                "7:pm:1:1",
                250,
                null,
                null,
                null,
                "openai-compatible",
                "model-a",
                "ProductManagerAgent_v1",
                "deep",
                "profile=DEEP;node=product_manager;route=deep",
                false,
                null,
                calls,
                inputTokens,
                outputTokens,
                15,
                new BigDecimal(estimatedCost),
                "correlation-1",
                null,
                null
        );
    }
}
