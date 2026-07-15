package com.autospec.workflow.transport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "autospec.workflow.outbox",
        name = "enabled",
        havingValue = "true"
)
public class WorkflowOutboxPublishingConfiguration {
    @Bean
    WorkflowOutboxPublishingJob workflowOutboxPublishingJob(
            WorkflowOutboxPublisher publisher,
            @Value("${autospec.workflow.outbox.batch-size:20}") int batchSize
    ) {
        return new WorkflowOutboxPublishingJob(publisher, batchSize);
    }
}
