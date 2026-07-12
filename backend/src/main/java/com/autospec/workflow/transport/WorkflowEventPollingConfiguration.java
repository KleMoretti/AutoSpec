package com.autospec.workflow.transport;

import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "autospec.workflow.events.polling",
        name = "enabled",
        havingValue = "true"
)
public class WorkflowEventPollingConfiguration {
    @Bean
    WorkflowEventConsumer workflowEventConsumer(
            ProcessedWorkflowEventMapper processedEventMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowRunReconciliationTrigger reconciliationTrigger,
            ObjectMapper objectMapper
    ) {
        return new WorkflowEventConsumer(
                processedEventMapper, nodeRunMapper, reconciliationTrigger, objectMapper
        );
    }

    @Bean
    WorkflowEventStreamClient workflowEventStreamClient(
            StringRedisTemplate redisTemplate,
            @Value("${autospec.workflow.events.polling.block-timeout:1s}") String blockTimeout
    ) {
        Duration parsedBlockTimeout = DurationStyle.detectAndParse(blockTimeout);
        return new RedisWorkflowEventStreamClient(redisTemplate, parsedBlockTimeout);
    }

    @Bean
    WorkflowEventPoller workflowEventPoller(
            WorkflowEventStreamClient streamClient,
            WorkflowEventConsumer eventConsumer,
            @Value("${autospec.workflow.events.polling.consumer-name:control-plane}") String consumerName,
            @Value("${autospec.workflow.events.polling.batch-size:10}") int batchSize
    ) {
        return new WorkflowEventPoller(streamClient, eventConsumer::consume, consumerName, batchSize);
    }

    @Bean
    WorkflowEventPollingJob workflowEventPollingJob(WorkflowEventPoller poller) {
        return new WorkflowEventPollingJob(poller);
    }
}
