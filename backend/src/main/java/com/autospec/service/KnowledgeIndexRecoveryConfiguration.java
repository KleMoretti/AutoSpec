package com.autospec.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "autospec.knowledge.recovery",
        name = "enabled",
        havingValue = "true"
)
public class KnowledgeIndexRecoveryConfiguration {
    @Bean
    KnowledgeIndexRecoveryJob knowledgeIndexRecoveryJob(
            KnowledgeDocumentService knowledgeDocumentService,
            ArtifactService artifactService,
            KnowledgeIndexService knowledgeIndexService,
            ArtifactApprovalOutboxService approvalOutboxService,
            @Value("${autospec.knowledge.recovery.batch-size:20}") int batchSize
    ) {
        return new KnowledgeIndexRecoveryJob(
                knowledgeDocumentService,
                artifactService,
                knowledgeIndexService,
                approvalOutboxService,
                batchSize
        );
    }
}
