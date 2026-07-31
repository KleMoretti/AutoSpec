package com.autospec.workflow.transport;

import com.autospec.mapper.WorkflowOutboxMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "autospec.observability.workflow-backlog",
        name = "enabled",
        havingValue = "true"
)
public class WorkflowBacklogMetricsConfiguration {

    @Bean
    WorkflowBacklogMetrics workflowBacklogMetrics(
            WorkflowOutboxMapper outboxMapper,
            StringRedisTemplate redisTemplate,
            MeterRegistry registry,
            @Value("${autospec.observability.workflow-backlog.event-stream:autospec.workflow.events}")
            String eventStream,
            @Value("${autospec.observability.workflow-backlog.consumer-group:autospec-control-plane}")
            String consumerGroup
    ) {
        return new WorkflowBacklogMetrics(
                outboxMapper,
                redisTemplate,
                registry,
                eventStream,
                consumerGroup
        );
    }
}
