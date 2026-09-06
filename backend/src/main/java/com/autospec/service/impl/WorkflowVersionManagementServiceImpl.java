package com.autospec.service.impl;

import com.autospec.entity.WorkflowDefinition;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.WorkflowDefinitionMapper;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.WorkflowVersionManagementService;
import com.autospec.service.WorkflowExecutionBundleService;
import com.autospec.util.CanonicalJson;
import com.autospec.workflow.runtime.CompiledWorkflow;
import com.autospec.workflow.runtime.DagCompiler;
import com.autospec.workflow.runtime.WorkflowSnapshotParser;
import com.autospec.workflow.runtime.WorkflowExecutableContractValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

@Service
public class WorkflowVersionManagementServiceImpl implements WorkflowVersionManagementService {
    private final WorkflowDefinitionMapper definitionMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final WorkflowExecutionBundleService executionBundleService;

    public WorkflowVersionManagementServiceImpl(
            WorkflowDefinitionMapper definitionMapper,
            WorkflowVersionMapper versionMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            WorkflowExecutionBundleService executionBundleService
    ) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.executionBundleService = executionBundleService;
    }

    @Override
    @Transactional
    public WorkflowVersion createDraft(CreateDraftCommand command) {
        if (command.workflowKey() == null || command.workflowKey().isBlank()) {
            throw badRequest("workflowKey is required");
        }
        if (command.version() == null || command.version().isBlank()) {
            throw badRequest("version is required");
        }
        if (command.specJson() == null || command.specJson().isBlank()) {
            throw badRequest("specJson is required");
        }
        WorkflowDefinition definition = definitionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowDefinition>()
                        .eq(WorkflowDefinition::getWorkflowKey, command.workflowKey().trim())
        );
        LocalDateTime now = LocalDateTime.now();
        if (definition == null) {
            definition = new WorkflowDefinition();
            definition.setWorkflowKey(command.workflowKey().trim());
            definition.setName(command.name() == null || command.name().isBlank()
                    ? command.workflowKey().trim()
                    : command.name().trim());
            definition.setDescription(command.description());
            definition.setStatus("ACTIVE");
            definition.setCreatedAt(now);
            definition.setUpdatedAt(now);
            definitionMapper.insert(definition);
        }
        long duplicates = versionMapper.selectCount(new LambdaQueryWrapper<WorkflowVersion>()
                .eq(WorkflowVersion::getDefinitionId, definition.getId())
                .eq(WorkflowVersion::getVersion, command.version().trim()));
        if (duplicates > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow version already exists");
        }
        WorkflowVersion version = new WorkflowVersion();
        version.setDefinitionId(definition.getId());
        version.setVersion(command.version().trim());
        version.setSpecJson(command.specJson());
        version.setContentHash(CanonicalJson.sha256(command.specJson()));
        version.setStatus("DRAFT");
        version.setCreatedAt(now);
        versionMapper.insert(version);
        return version;
    }

    @Override
    public ValidationResult validate(long versionId) {
        WorkflowVersion version = requireVersion(versionId);
        try {
            var document = snapshotParser.parse(version.getSpecJson());
            WorkflowExecutableContractValidator.validate(document);
            if (!version.getVersion().equals(document.version())) {
                return new ValidationResult(
                        versionId,
                        false,
                        List.of("Spec version does not match workflow version"),
                        List.of()
                );
            }
            CompiledWorkflow graph = dagCompiler.compile(document);
            List<String> runtimeErrors = graph.nodes().values().stream()
                    .filter(node -> node.agentName() == null || node.agentName().isBlank())
                    .map(node -> "Node agent_name is required: " + node.nodeId())
                    .toList();
            return new ValidationResult(
                    versionId,
                    runtimeErrors.isEmpty(),
                    runtimeErrors,
                    graph.topologicalLayers()
            );
        } catch (IllegalArgumentException exception) {
            return new ValidationResult(versionId, false, List.of(exception.getMessage()), List.of());
        }
    }

    @Override
    @Transactional
    public WorkflowVersion publish(long versionId) {
        WorkflowVersion version = requireVersion(versionId);
        if ("PUBLISHED".equals(version.getStatus())) {
            return version;
        }
        if (!"DRAFT".equals(version.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only draft versions can be published");
        }
        String canonicalHash = CanonicalJson.sha256(version.getSpecJson());
        if (!canonicalHash.equals(version.getContentHash())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Workflow draft content hash does not match canonical JSON"
            );
        }
        ValidationResult validation = validate(versionId);
        if (!validation.valid()) {
            throw badRequest("Invalid workflow version: " + String.join("; ", validation.errors()));
        }
        executionBundleService.ensureFor(version, snapshotParser.parse(version.getSpecJson()));
        LocalDateTime publishedAt = LocalDateTime.now();
        int updated = versionMapper.update(
                null,
                new LambdaUpdateWrapper<WorkflowVersion>()
                        .eq(WorkflowVersion::getId, versionId)
                        .eq(WorkflowVersion::getStatus, "DRAFT")
                        .set(WorkflowVersion::getStatus, "PUBLISHED")
                        .set(WorkflowVersion::getPublishedAt, publishedAt)
                        .set(WorkflowVersion::getImmutableAt, publishedAt)
        );
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Workflow version was changed before publication"
            );
        }
        return versionMapper.selectById(versionId);
    }

    private WorkflowVersion requireVersion(long versionId) {
        WorkflowVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow version not found");
        }
        return version;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
