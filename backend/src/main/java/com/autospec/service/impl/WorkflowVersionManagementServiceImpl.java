package com.autospec.service.impl;

import com.autospec.entity.WorkflowDefinition;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.WorkflowDefinitionMapper;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.WorkflowVersionManagementService;
import com.autospec.workflow.runtime.CompiledWorkflow;
import com.autospec.workflow.runtime.DagCompiler;
import com.autospec.workflow.runtime.WorkflowSnapshotParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class WorkflowVersionManagementServiceImpl implements WorkflowVersionManagementService {
    private final WorkflowDefinitionMapper definitionMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;

    public WorkflowVersionManagementServiceImpl(
            WorkflowDefinitionMapper definitionMapper,
            WorkflowVersionMapper versionMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler
    ) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
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
        version.setContentHash(sha256(command.specJson()));
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
        ValidationResult validation = validate(versionId);
        if (!validation.valid()) {
            throw badRequest("Invalid workflow version: " + String.join("; ", validation.errors()));
        }
        version.setStatus("PUBLISHED");
        version.setPublishedAt(LocalDateTime.now());
        versionMapper.updateById(version);
        return versionMapper.selectById(versionId);
    }

    private WorkflowVersion requireVersion(long versionId) {
        WorkflowVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow version not found");
        }
        return version;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
