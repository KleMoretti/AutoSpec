package com.autospec.workflow.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "autospec.workflow.recovery",
        name = "enabled",
        havingValue = "true"
)
public class WorkflowRecoveryConfiguration {

    @Bean
    WorkflowRecoveryJob workflowRecoveryJob(
            WorkflowRecoveryService recoveryService,
            @Value("${autospec.workflow.recovery.lease-timeout:30s}") String leaseTimeout
    ) {
        Duration parsedLeaseTimeout = DurationStyle.detectAndParse(leaseTimeout);
        return new WorkflowRecoveryJob(recoveryService, Clock.systemDefaultZone(), parsedLeaseTimeout);
    }
}
