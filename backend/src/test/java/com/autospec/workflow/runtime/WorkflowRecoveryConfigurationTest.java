package com.autospec.workflow.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowRecoveryConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(WorkflowRecoveryService.class, () -> mock(WorkflowRecoveryService.class))
            .withUserConfiguration(WorkflowRecoveryConfiguration.class);

    @Test
    void recoverySchedulerIsDisabledByDefault() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(WorkflowRecoveryJob.class));
    }

    @Test
    void recoverySchedulerIsCreatedWhenEnabled() {
        contextRunner.withPropertyValues(
                        "autospec.workflow.recovery.enabled=true",
                        "autospec.workflow.recovery.lease-timeout=45s",
                        "autospec.workflow.recovery.initial-delay=3600000"
                )
                .run(context -> assertThat(context).hasSingleBean(WorkflowRecoveryJob.class));
    }
}
