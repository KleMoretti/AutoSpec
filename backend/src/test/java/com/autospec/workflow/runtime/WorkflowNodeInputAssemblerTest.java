package com.autospec.workflow.runtime;

import com.autospec.dto.ContextConflictResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.service.ProjectMemoryService;
import com.autospec.service.RequirementBaselineService;
import com.autospec.util.ContentHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowNodeInputAssemblerTest {
    @Test
    void assemblesApprovedArtifactIdentityBaselineAndResolvedMemoryConflict() throws Exception {
        var mapper = new ObjectMapper();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "input-assembler-test"), WorkflowNodeRun.class);
        var nodes = mock(WorkflowNodeRunMapper.class);
        var artifacts = mock(ArtifactMapper.class);
        var memory = mock(ProjectMemoryService.class);
        var baselines = mock(RequirementBaselineService.class);
        var assembler = new WorkflowNodeInputAssembler(nodes, mapper, null, null, memory, baselines);
        assembler.setArtifactMapper(artifacts);
        var graph = new DagCompiler().compile(new WorkflowSnapshotParser(mapper).parse(Files.readString(
                Path.of("..", "agent-engine", "contracts", "autospec-pm-schema-repair-v12.workflow.json"))));
        var pm = new WorkflowNodeRun();
        pm.setId(10L);
        pm.setNodeId("product_manager");
        pm.setStatus("SUCCEEDED");
        pm.setOutputJson("{\"project_name\":\"original\"}");
        var target = new WorkflowNodeRun();
        target.setId(11L);
        target.setWorkflowRunId(44L);
        target.setNodeId("architect");
        target.setInputJson("{\"_autospec_project_id\":9,\"clarification_context\":{\"clarification_responses\":[{\"conflict_resolutions\":[{\"conflict_id\":\"c-1\",\"resolution\":\"KEEP_CURRENT\"}]}]}}");
        var prd = new Artifact();
        prd.setId(20L);
        prd.setType("PRD");
        prd.setVersion(2);
        prd.setContent("{\"project_name\":\"approved\"}");
        when(nodes.selectList(any())).thenReturn(List.of(pm));
        when(nodes.update(any(), any())).thenReturn(1);
        when(artifacts.selectOne(any())).thenReturn(prd);
        when(baselines.inputForRun(44L)).thenReturn(Map.of("baseline_id", "RB-44-1"));
        when(memory.conflictsForProject(any(), any())).thenReturn(List.of(new ContextConflictResponse(
                "c-1", 9L, "CONSTRAINT", "scope", mapper.readTree("\"legacy\""), "memory:1", 1,
                true, "scope conflict", "UNRESOLVED")));

        assembler.assemble(graph, target);

        var input = mapper.readTree(target.getInputJson());
        assertThat(input.path("prd").path("project_name").asText()).isEqualTo("approved");
        assertThat(input.path("artifact_bindings").path("prd").path("version").asInt()).isEqualTo(2);
        assertThat(input.path("artifact_bindings").path("prd").path("content_hash").asText())
                .isEqualTo(ContentHash.sha256(prd.getContent()));
        assertThat(input.path("requirement_baseline").path("baseline_id").asText()).isEqualTo("RB-44-1");
        assertThat(input.path("context_conflicts").get(0).path("status").asText()).isEqualTo("RESOLVED");
        assertThat(input.path("context_conflicts").get(0).has("project_id")).isFalse();
    }
}
