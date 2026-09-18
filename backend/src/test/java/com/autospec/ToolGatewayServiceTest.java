package com.autospec;

import com.autospec.dto.ToolGatewayRequest;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.mapper.WorkflowToolCallFactMapper;
import com.autospec.service.ArtifactService;
import com.autospec.service.AuditEventService;
import com.autospec.service.KnowledgeIndexService;
import com.autospec.service.ToolGatewayService;
import com.autospec.service.WorkflowTraceService;
import com.autospec.util.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ToolGatewayServiceTest {
    @Test
    void resolvesPolicyFromVerifiedBundleAndIgnoresBusinessInputPolicy() throws Exception {
        var mapper = new ObjectMapper();
        var document = mapper.readTree(Files.readString(Path.of("..", "agent-engine", "contracts",
                "autospec-v5-agent-execution-v3-c.workflow.json")));
        var bundle = mapper.createObjectNode();
        bundle.set("nodes", document.path("nodes"));
        var run = new WorkflowRun();
        run.setId(1L);
        run.setProjectId(2L);
        run.setInitiatedByUserId(3L);
        run.setExecutionBundleJson(bundle.toString());
        run.setExecutionBundleHash(CanonicalJson.sha256(bundle.toString()));
        var node = new WorkflowNodeRun();
        node.setId(4L);
        node.setWorkflowRunId(1L);
        node.setNodeId("backend_engineer");
        node.setExecutionId("1:backend_engineer:1:1");
        node.setExecutionBundleHash(run.getExecutionBundleHash());
        node.setFencingToken(1L);
        node.setStatus("RUNNING");
        node.setInputJson("{\"requirement\":\"inventory\"}");
        var runs = mock(WorkflowRunMapper.class);
        var nodes = mock(WorkflowNodeRunMapper.class);
        var facts = mock(WorkflowToolCallFactMapper.class);
        when(runs.selectById(1L)).thenReturn(run);
        when(nodes.selectById(4L)).thenReturn(node);
        when(facts.selectCount(any())).thenReturn(0L);
        var service = new ToolGatewayService(runs, nodes, facts, mock(ArtifactService.class),
                mock(KnowledgeIndexService.class), mock(WorkflowTraceService.class), mock(AuditEventService.class), mapper);
        var arguments = mapper.createObjectNode().put("node_id", "backend_engineer");
        var request = new ToolGatewayRequest("request-1", node.getExecutionId(), 1L, 4L, node.getNodeId(),
                3L, 2L, 1L, System.currentTimeMillis() + 60000, run.getExecutionBundleHash(),
                "c917e219df519108526e1dda8091c26d2060dd1a5743ef58dd07e0f830d490bd", "tool-1", "contract.lookup", "v1",
                arguments, CanonicalJson.sha256(arguments.toString()), 32000, "trace", null, null);
        assertThat(service.execute(request).status()).isEqualTo("SUCCEEDED");

        // Business input cannot replace the allowlist frozen at publication.
        node.setInputJson("{\"tool_policy\":{\"enabled\":false}}");
        assertThat(service.execute(request).status()).isEqualTo("SUCCEEDED");

        ((ObjectNode) bundle.path("nodes").get(2).path("tool_policy")).put("max_calls", 32);
        run.setExecutionBundleJson(bundle.toString());
        assertThat(service.execute(request).errorCode()).isEqualTo("BUNDLE_HASH_MISMATCH");
        run.setExecutionBundleJson(null);
        assertThat(service.execute(request).errorCode()).isEqualTo("BUNDLE_NOT_FOUND");
    }
}
