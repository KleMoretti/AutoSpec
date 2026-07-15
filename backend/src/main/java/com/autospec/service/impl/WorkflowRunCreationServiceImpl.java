package com.autospec.service.impl;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowTransition;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.mapper.WorkflowTransitionMapper;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.WorkflowRunCreationService;
import com.autospec.workflow.runtime.CompiledWorkflow;
import com.autospec.workflow.runtime.DagCompiler;
import com.autospec.workflow.runtime.WorkflowHandlerCatalog;
import com.autospec.workflow.runtime.WorkflowRunReconciliationService;
import com.autospec.workflow.runtime.WorkflowSnapshotParser;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowRunCreationServiceImpl implements WorkflowRunCreationService {
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowTransitionMapper transitionMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final WorkflowHandlerCatalog handlerCatalog;
    private final WorkflowRunReconciliationService reconciliationService;
    private final ObjectMapper objectMapper;

    public WorkflowRunCreationServiceImpl(
            WorkflowVersionMapper versionMapper,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowTransitionMapper transitionMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            WorkflowHandlerCatalog handlerCatalog,
            WorkflowRunReconciliationService reconciliationService,
            ObjectMapper objectMapper
    ) {
        this.versionMapper = versionMapper;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.transitionMapper = transitionMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.handlerCatalog = handlerCatalog;
        this.reconciliationService = reconciliationService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public WorkflowRun start(StartCommand command) {
        String idempotencyKey = command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString()
                : command.idempotencyKey().trim();
        WorkflowRun duplicate = runMapper.selectOne(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getProjectId, command.projectId())
                .eq(WorkflowRun::getOperation, "GENERATE_V5")
                .eq(WorkflowRun::getIdempotencyKey, idempotencyKey));
        if (duplicate != null) {
            return duplicate;
        }
        WorkflowVersion version = versionMapper.selectById(command.workflowVersionId());
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow version not found");
        }
        if (!"PUBLISHED".equals(version.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow version must be PUBLISHED");
        }
        var document = snapshotParser.parse(version.getSpecJson());
        CompiledWorkflow graph = dagCompiler.compile(document);
        Map<String, HandlerRef> handlers = graph.nodes().entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> handler(entry.getValue())
                ));
        validateInput(command.inputJson());

        LocalDateTime now = LocalDateTime.now();
        WorkflowRun run = new WorkflowRun();
        run.setProjectId(command.projectId());
        run.setOperation("GENERATE_V5");
        run.setIdempotencyKey(idempotencyKey);
        run.setCorrelationId(UUID.randomUUID().toString());
        run.setWorkflowVersionId(version.getId());
        run.setWorkflowSnapshotJson(version.getSpecJson());
        run.setReviewRound(0);
        run.setMaxReviewRounds(document.maxReviewRounds());
        run.setLockVersion(0);
        run.setLastHeartbeatAt(now);
        run.setStatus("RUNNING");
        run.setResponseStatus("GENERATING");
        run.setResponsePercent(0);
        run.setStartedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);

        for (String nodeId : graph.nodes().keySet().stream().sorted().toList()) {
            WorkflowNodeDocument nodeSpec = graph.nodes().get(nodeId);
            HandlerRef handler = handlers.get(nodeId);
            WorkflowNodeRun node = new WorkflowNodeRun();
            node.setWorkflowRunId(run.getId());
            node.setNodeId(nodeId);
            node.setRevision(1);
            node.setAttempt(1);
            node.setExecutionId(run.getId() + ":" + nodeId + ":1:1");
            node.setStatus("PENDING");
            node.setHandlerKey(handler.key());
            node.setHandlerVersion(handler.version());
            node.setTimeoutMs(nodeSpec.timeoutMs());
            node.setInputJson(command.inputJson());
            node.setLockVersion(0);
            node.setCreatedAt(now);
            node.setUpdatedAt(now);
            nodeRunMapper.insert(node);
        }
        transition(run, now);
        reconciliationService.reconcile(run.getId());
        return runMapper.selectById(run.getId());
    }

    private HandlerRef handler(WorkflowNodeDocument node) {
        String agentName = node.agentName();
        if (agentName == null || agentName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Node agent_name is required: " + node.nodeId()
            );
        }
        int marker = agentName.lastIndexOf("_v");
        String key = marker > 0 ? agentName.substring(0, marker) : agentName;
        String version = marker > 0 ? agentName.substring(marker + 1) : "v1";
        if (!handlerCatalog.isAvailable(key, version)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "RUNTIME_VERSION_UNAVAILABLE: " + key + ":" + version
            );
        }
        return new HandlerRef(key, version);
    }

    private void validateInput(String inputJson) {
        try {
            if (inputJson == null || !objectMapper.readTree(inputJson).isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input must be a JSON object");
            }
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input must be valid JSON", exception);
        }
    }

    private void transition(WorkflowRun run, LocalDateTime now) {
        WorkflowTransition transition = new WorkflowTransition();
        transition.setWorkflowRunId(run.getId());
        transition.setToStatus("RUNNING");
        transition.setEventType("WORKFLOW_RUN_CREATED");
        transition.setEventId(UUID.randomUUID().toString());
        transition.setMetadataJson("{\"workflow_version_id\":" + run.getWorkflowVersionId() + "}");
        transition.setCreatedAt(now);
        transitionMapper.insert(transition);
    }

    private record HandlerRef(String key, String version) {
    }
}
