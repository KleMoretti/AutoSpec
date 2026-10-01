package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.WorkflowExecutionBundleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ParallelWorkflowBundleTest {
    @Autowired WorkflowVersionMapper versions;
    @Autowired WorkflowSnapshotParser parser;
    @Autowired WorkflowExecutionBundleService bundles;
    @Autowired WorkflowHandlerCatalog handlers;

    @Test
    void publishedParallelVersionFreezesAllNewPromptsAndHandlers() {
        WorkflowVersion version = versions.selectOne(new LambdaQueryWrapper<WorkflowVersion>()
                .eq(WorkflowVersion::getVersion, "pm-schema-repair-v12"));
        assertThat(version).isNotNull();
        assertThat(version.getStatus()).isEqualTo("PUBLISHED");
        assertThat(version.getImmutableAt()).isNotNull();
        var spec = parser.parse(version.getSpecJson());
        for (var node : spec.nodes()) {
            int marker = node.agentName().lastIndexOf("_v");
            assertThat(handlers.isAvailable(node.agentName().substring(0, marker),
                    node.agentName().substring(marker + 1))).isTrue();
        }
        var bundle = bundles.ensureFor(version, spec);
        assertThat(bundle.getBundleHash()).hasSize(64);
        assertThat(bundle.getBundleJson()).contains("architect_schema", "frontend_schema");
    }
}
