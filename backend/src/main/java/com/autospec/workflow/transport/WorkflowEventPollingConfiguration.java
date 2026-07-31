package com.autospec.workflow.transport;

import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.observability.WorkflowEventTracer;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.autospec.workflow.runtime.WorkflowApprovalCoordinator;
import com.autospec.workflow.runtime.WorkflowArtifactProjector;
import com.autospec.workflow.runtime.ReviewerReworkCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
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
            ObjectProvider<WorkflowRunMapper> runMapperProvider,
            WorkflowRunReconciliationTrigger reconciliationTrigger,
            WorkflowFailureDecisionService failureDecisionService,
            ObjectProvider<WorkflowApprovalCoordinator> approvalCoordinatorProvider,
            ObjectProvider<WorkflowArtifactProjector> artifactProjectorProvider,
            ObjectProvider<ReviewerReworkCoordinator> reworkCoordinatorProvider,
            ObjectProvider<OpenTelemetry> openTelemetryProvider,
            ObjectMapper objectMapper
    ) {
        return new WorkflowEventConsumer(
                processedEventMapper, nodeRunMapper, reconciliationTrigger,
                failureDecisionService,
                objectMapper,
                approvalCoordinatorProvider.getIfAvailable(WorkflowApprovalCoordinator::none),
                artifactProjectorProvider.getIfAvailable(WorkflowArtifactProjector::none),
                reworkCoordinatorProvider.getIfAvailable(ReviewerReworkCoordinator::none),
                runMapperProvider.getIfAvailable(),
                new WorkflowEventTracer(
                        openTelemetryProvider.getIfAvailable(OpenTelemetry::noop)
                )
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
            @Value("${autospec.workflow.events.polling.batch-size:10}") int batchSize,
            @Value("${autospec.workflow.events.polling.claim-min-idle:30s}") String claimMinIdle,
            ObjectProvider<WorkflowTransportMetrics> metricsProvider
    ) {
        Duration parsedClaimMinIdle = DurationStyle.detectAndParse(claimMinIdle);
        return new WorkflowEventPoller(
                streamClient,
                eventConsumer::consume,
                consumerName,
                batchSize,
                parsedClaimMinIdle,
                metricsProvider.getIfAvailable(WorkflowTransportMetrics::isolated)
        );
    }

    @Bean
    WorkflowEventPollingJob workflowEventPollingJob(WorkflowEventPoller poller) {
        return new WorkflowEventPollingJob(poller);
    }
}
