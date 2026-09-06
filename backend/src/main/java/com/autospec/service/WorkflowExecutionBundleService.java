package com.autospec.service;

import com.autospec.entity.WorkflowExecutionBundle;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.WorkflowExecutionBundleMapper;
import com.autospec.util.CanonicalJson;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.autospec.workflow.spec.WorkflowSpecDocument;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Comparator;

/** Creates and verifies the immutable execution configuration used by a workflow run. */
@Service
public class WorkflowExecutionBundleService {
    public static final String SCHEMA_VERSION = "execution-bundle-v1";
    public static final String BUNDLE_VERSION = "v1";

    private final WorkflowExecutionBundleMapper bundleMapper;
    private final PromptRegistryService promptRegistryService;
    private final ObjectMapper objectMapper;

    public WorkflowExecutionBundleService(
            WorkflowExecutionBundleMapper bundleMapper,
            PromptRegistryService promptRegistryService,
            ObjectMapper objectMapper
    ) {
        this.bundleMapper = bundleMapper;
        this.promptRegistryService = promptRegistryService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowExecutionBundle ensureFor(
            WorkflowVersion workflowVersion,
            WorkflowSpecDocument spec
    ) {
        WorkflowExecutionBundle existing = bundleMapper.selectOne(new LambdaQueryWrapper<WorkflowExecutionBundle>()
                .eq(WorkflowExecutionBundle::getWorkflowVersionId, workflowVersion.getId())
                .eq(WorkflowExecutionBundle::getBundleVersion, BUNDLE_VERSION)
                .last("limit 1"));
        if (existing != null) {
            verify(existing);
            return existing;
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("bundle_version", BUNDLE_VERSION);
        root.put("workflow_key", spec.workflowKey());
        root.put("workflow_version", spec.version());
        root.put("workflow_spec_hash", CanonicalJson.sha256(workflowVersion.getSpecJson()));
        root.put("protocol_version", spec.protocolVersion());
        ObjectNode runtime = root.putObject("runtime");
        runtime.put("max_parallel_nodes", spec.maxParallelNodes());
        runtime.put("max_review_rounds", spec.maxReviewRounds());
        ArrayNode nodes = root.putArray("nodes");
        spec.nodes().stream()
                .sorted(Comparator.comparing(WorkflowNodeDocument::nodeId))
                .forEach(node -> nodes.add(nodeSnapshot(node)));

        String bundleJson = CanonicalJson.normalize(root.toString());
        WorkflowExecutionBundle bundle = new WorkflowExecutionBundle();
        bundle.setWorkflowVersionId(workflowVersion.getId());
        bundle.setBundleVersion(BUNDLE_VERSION);
        bundle.setBundleJson(bundleJson);
        bundle.setBundleHash(CanonicalJson.sha256(bundleJson));
        bundle.setCreatedAt(LocalDateTime.now());
        try {
            bundleMapper.insert(bundle);
        } catch (RuntimeException exception) {
            WorkflowExecutionBundle concurrent = bundleMapper.selectOne(
                    new LambdaQueryWrapper<WorkflowExecutionBundle>()
                            .eq(WorkflowExecutionBundle::getWorkflowVersionId, workflowVersion.getId())
                            .eq(WorkflowExecutionBundle::getBundleVersion, BUNDLE_VERSION)
                            .last("limit 1")
            );
            if (concurrent == null) {
                throw exception;
            }
            verify(concurrent);
            return concurrent;
        }
        return bundle;
    }

    public WorkflowExecutionBundle require(long workflowVersionId) {
        WorkflowExecutionBundle bundle = bundleMapper.selectOne(new LambdaQueryWrapper<WorkflowExecutionBundle>()
                .eq(WorkflowExecutionBundle::getWorkflowVersionId, workflowVersionId)
                .eq(WorkflowExecutionBundle::getBundleVersion, BUNDLE_VERSION)
                .last("limit 1"));
        if (bundle == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Execution bundle is missing");
        }
        verify(bundle);
        return bundle;
    }

    private ObjectNode nodeSnapshot(WorkflowNodeDocument node) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("node_id", node.nodeId());
        result.put("handler_key", handlerKey(node.agentName()));
        result.put("handler_version", handlerVersion(node.agentName()));
        result.put("input_schema", node.inputSchema());
        result.put("input_schema_hash", node.inputSchemaHash());
        result.put("output_schema", node.outputSchema());
        result.put("output_schema_hash", node.outputSchemaHash());
        result.put("artifact_type", node.artifactType());
        result.put("prompt_key", node.promptKey());
        result.put("prompt_version", node.promptVersion());
        result.put("prompt_checksum", node.promptChecksum());
        result.set("context_policy", node.contextPolicy().deepCopy());
        result.set("model_policy", node.modelPolicy().deepCopy());
        result.set("retry_policy", node.retryPolicy().deepCopy());
        result.set("fallback", node.fallback().deepCopy());
        result.set("tool_policy", node.toolPolicy().deepCopy());
        if (node.promptKey() != null && !node.promptKey().isBlank()
                && node.promptVersion() != null && !node.promptVersion().isBlank()) {
            PromptVersionSnapshot prompt = promptSnapshot(node);
            ObjectNode promptNode = result.putObject("prompt");
            promptNode.put("key", prompt.key());
            promptNode.put("version", prompt.version());
            promptNode.put("checksum", prompt.checksum());
            promptNode.put("content", prompt.content());
        }
        return result;
    }

    private PromptVersionSnapshot promptSnapshot(WorkflowNodeDocument node) {
        String key = promptRegistryService.normalizePromptKey(node.promptKey());
        var prompt = promptRegistryService.activePrompt(key);
        String checksum = prompt.getChecksum() == null ? "" : prompt.getChecksum().replaceFirst("^sha256:", "");
        if (!node.promptVersion().equals(prompt.getVersion())
                || !node.promptChecksum().equals(checksum)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Active prompt does not match workflow contract: " + node.nodeId()
            );
        }
        return new PromptVersionSnapshot(key, prompt.getVersion(), checksum, prompt.getContent());
    }

    private void verify(WorkflowExecutionBundle bundle) {
        if (bundle.getBundleJson() == null
                || !CanonicalJson.sha256(bundle.getBundleJson()).equals(bundle.getBundleHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Execution bundle checksum mismatch");
        }
    }

    private String handlerKey(String agentName) {
        int marker = agentName == null ? -1 : agentName.lastIndexOf("_v");
        return marker > 0 ? agentName.substring(0, marker) : agentName;
    }

    private String handlerVersion(String agentName) {
        int marker = agentName == null ? -1 : agentName.lastIndexOf("_v");
        return marker > 0 ? agentName.substring(marker + 1) : "v1";
    }

    private record PromptVersionSnapshot(String key, String version, String checksum, String content) {
    }
}
