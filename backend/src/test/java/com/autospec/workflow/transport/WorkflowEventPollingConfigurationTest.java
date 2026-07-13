package com.autospec.workflow.transport;

import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowEventPollingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ProcessedWorkflowEventMapper.class, () -> mock(ProcessedWorkflowEventMapper.class))
            .withBean(WorkflowNodeRunMapper.class, () -> mock(WorkflowNodeRunMapper.class))
            .withBean(WorkflowFailureDecisionService.class,
                    () -> mock(WorkflowFailureDecisionService.class))
            .withBean(WorkflowRunReconciliationTrigger.class,
                    () -> mock(WorkflowRunReconciliationTrigger.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(WorkflowEventPollingConfiguration.class);

    @Test
    void pollingInfrastructureIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(WorkflowEventPoller.class);
            assertThat(context).doesNotHaveBean(WorkflowEventPollingJob.class);
        });
    }

    @Test
    void pollingInfrastructureIsCreatedWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "autospec.workflow.events.polling.enabled=true",
                        "autospec.workflow.events.polling.consumer-name=test-control",
                        "autospec.workflow.events.polling.batch-size=25",
                        "autospec.workflow.events.polling.initial-delay=3600000"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkflowEventConsumer.class);
                    assertThat(context).hasSingleBean(WorkflowEventStreamClient.class);
                    assertThat(context).hasSingleBean(WorkflowEventPoller.class);
                    assertThat(context).hasSingleBean(WorkflowEventPollingJob.class);
                });
    }
}
