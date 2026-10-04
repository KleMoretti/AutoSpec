package com.autospec.service;

import com.autospec.dto.ProjectMemoryFactResponse;
import com.autospec.dto.ContextConflictResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.ProjectMemoryFact;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.mapper.ProjectMemoryFactMapper;
import com.autospec.util.ContentHash;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class ProjectMemoryService {
    public static final Set<String> FACT_TYPES = Set.of(
            "REQUIREMENT", "DECISION", "CONSTRAINT", "ENTITY", "API", "ARTIFACT"
    );

    private final ProjectMemoryFactMapper factMapper;
    private final ObjectMapper objectMapper;

    public ProjectMemoryService(ProjectMemoryFactMapper factMapper, ObjectMapper objectMapper) {
        this.factMapper = factMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void projectArtifact(
            Artifact artifact,
            WorkflowNodeRun nodeRun,
            Long workflowRunId
    ) {
        JsonNode content = json(artifact.getContent());
        Map<FactIdentity, JsonNode> extracted = extract(artifact, content);
        LocalDateTime now = LocalDateTime.now();
        Set<FactIdentity> present = new LinkedHashSet<>(extracted.keySet());

        List<ProjectMemoryFact> priorArtifactFacts = factMapper.selectList(
                new LambdaQueryWrapper<ProjectMemoryFact>()
                        .eq(ProjectMemoryFact::getProjectId, artifact.getProjectId())
                        .eq(ProjectMemoryFact::getSourceArtifactType, artifact.getType())
                        .eq(ProjectMemoryFact::getConflictStatus, "ACTIVE")
                        .isNull(ProjectMemoryFact::getValidUntilAt)
        );
        for (ProjectMemoryFact prior : priorArtifactFacts) {
            FactIdentity identity = new FactIdentity(prior.getFactType(), prior.getFactKey());
            if (!present.contains(identity)) {
                close(prior, now);
            }
        }

        for (Map.Entry<FactIdentity, JsonNode> entry : extracted.entrySet()) {
            upsert(artifact, nodeRun, workflowRunId, entry.getKey(), entry.getValue(), now);
        }
    }

    public List<ProjectMemoryFactResponse> recallForNode(Long projectId, String nodeId) {
        return recall(projectId, factTypesForNode(nodeId));
    }

    public List<ProjectMemoryFactResponse> recallTrustedForNode(Long projectId, String nodeId) {
        return recallTrusted(projectId, factTypesForNode(nodeId));
    }

    public List<ProjectMemoryFactResponse> recallTrustedForNode(
            Long projectId,
            String nodeId,
            String query,
            int topK,
            int tokenBudget
    ) {
        List<ProjectMemoryFactResponse> facts = recallTrustedForNode(projectId, nodeId);
        Set<String> terms = terms(query);
        List<ProjectMemoryFactResponse> ranked = facts.stream()
                .sorted(Comparator
                        .comparingInt((ProjectMemoryFactResponse fact) -> relevance(fact, terms))
                        .reversed()
                        .thenComparing(ProjectMemoryFactResponse::factType)
                        .thenComparing(ProjectMemoryFactResponse::factKey))
                .toList();
        List<ProjectMemoryFactResponse> selected = new ArrayList<>();
        int used = 0;
        for (ProjectMemoryFactResponse fact : ranked) {
            if (selected.size() >= Math.max(1, topK)) {
                break;
            }
            int estimate = Math.max(1, fact.value().toString().length() / 4);
            if (tokenBudget > 0 && used + estimate > tokenBudget) {
                continue;
            }
            selected.add(fact);
            used += estimate;
        }
        return List.copyOf(selected);
    }

    public List<ContextConflictResponse> conflictsForProject(Long projectId, Set<String> factTypes) {
        LambdaQueryWrapper<ProjectMemoryFact> query = new LambdaQueryWrapper<ProjectMemoryFact>()
                .eq(ProjectMemoryFact::getProjectId, projectId)
                .eq(ProjectMemoryFact::getConflictStatus, "CONFLICT")
                .orderByAsc(ProjectMemoryFact::getFactType)
                .orderByAsc(ProjectMemoryFact::getFactKey)
                .orderByDesc(ProjectMemoryFact::getVersion);
        if (factTypes != null && !factTypes.isEmpty()) {
            query.in(ProjectMemoryFact::getFactType, factTypes);
        }
        return factMapper.selectList(query).stream()
                .map(fact -> new ContextConflictResponse(
                        "memory-conflict:" + fact.getId(),
                        fact.getProjectId(),
                        fact.getFactType(),
                        fact.getFactKey(),
                        json(fact.getValueJson()),
                        fact.getSourceRef(),
                        fact.getVersion(),
                        true,
                        "unapproved fact conflicts with an approved memory version",
                        "UNRESOLVED"
                ))
                .toList();
    }

    public List<ProjectMemoryFactResponse> recallTrusted(Long projectId, Set<String> factTypes) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<ProjectMemoryFact> query = new LambdaQueryWrapper<ProjectMemoryFact>()
                .eq(ProjectMemoryFact::getProjectId, projectId)
                .eq(ProjectMemoryFact::getTrustStatus, "APPROVED")
                .eq(ProjectMemoryFact::getConflictStatus, "ACTIVE")
                .le(ProjectMemoryFact::getValidFromAt, now)
                .and(wrapper -> wrapper.isNull(ProjectMemoryFact::getValidUntilAt)
                        .or().gt(ProjectMemoryFact::getValidUntilAt, now))
                .and(wrapper -> wrapper.isNull(ProjectMemoryFact::getExpiresAt)
                        .or().gt(ProjectMemoryFact::getExpiresAt, now))
                .orderByAsc(ProjectMemoryFact::getFactType)
                .orderByAsc(ProjectMemoryFact::getFactKey);
        if (factTypes != null && !factTypes.isEmpty()) {
            query.in(ProjectMemoryFact::getFactType, factTypes);
        }
        return factMapper.selectList(query).stream().map(this::response).toList();
    }

    public List<ProjectMemoryFactResponse> recall(Long projectId, Set<String> factTypes) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<ProjectMemoryFact> query = new LambdaQueryWrapper<ProjectMemoryFact>()
                .eq(ProjectMemoryFact::getProjectId, projectId)
                .eq(ProjectMemoryFact::getConflictStatus, "ACTIVE")
                .le(ProjectMemoryFact::getValidFromAt, now)
                .and(wrapper -> wrapper.isNull(ProjectMemoryFact::getValidUntilAt)
                        .or().gt(ProjectMemoryFact::getValidUntilAt, now))
                .and(wrapper -> wrapper.isNull(ProjectMemoryFact::getExpiresAt)
                        .or().gt(ProjectMemoryFact::getExpiresAt, now))
                .orderByAsc(ProjectMemoryFact::getFactType)
                .orderByAsc(ProjectMemoryFact::getFactKey);
        if (factTypes != null && !factTypes.isEmpty()) {
            query.in(ProjectMemoryFact::getFactType, factTypes);
        }
        return factMapper.selectList(query).stream().map(this::response).toList();
    }

    private void upsert(
            Artifact artifact,
            WorkflowNodeRun nodeRun,
            Long workflowRunId,
            FactIdentity identity,
            JsonNode value,
            LocalDateTime now
    ) {
        String canonical = canonical(value);
        String hash = ContentHash.sha256(canonical);
        ProjectMemoryFact current = factMapper.selectOne(
                new LambdaQueryWrapper<ProjectMemoryFact>()
                        .eq(ProjectMemoryFact::getProjectId, artifact.getProjectId())
                        .eq(ProjectMemoryFact::getFactType, identity.factType())
                        .eq(ProjectMemoryFact::getFactKey, identity.factKey())
                        .eq(ProjectMemoryFact::getConflictStatus, "ACTIVE")
                        .isNull(ProjectMemoryFact::getValidUntilAt)
                        .orderByDesc(ProjectMemoryFact::getVersion)
                        .last("limit 1")
        );
        if (current != null && hash.equals(current.getContentHash())) {
            return;
        }
        int version = current == null ? nextVersion(artifact.getProjectId(), identity) : current.getVersion() + 1;
        boolean approved = "APPROVED".equals(artifact.getStatus());
        boolean conflict = current != null
                && "APPROVED".equals(current.getTrustStatus())
                && !approved;
        if (current != null && !conflict) {
            close(current, now);
        }

        ProjectMemoryFact fact = new ProjectMemoryFact();
        fact.setProjectId(artifact.getProjectId());
        fact.setFactType(identity.factType());
        fact.setFactKey(identity.factKey());
        fact.setValueJson(canonical);
        fact.setContentHash(hash);
        fact.setVersion(version);
        fact.setConflictStatus(conflict ? "CONFLICT" : "ACTIVE");
        fact.setTrustStatus("APPROVED".equals(artifact.getStatus()) ? "APPROVED" : "UNTRUSTED");
        fact.setSourceType(sourceType(artifact));
        fact.setSourceRef("artifact:" + artifact.getId() + ":v" + artifact.getVersion());
        fact.setSourceWorkflowRunId(
                workflowRunId == null ? provenanceLong(artifact, "workflow_run_id") : workflowRunId
        );
        fact.setSourceNodeRunId(
                nodeRun == null ? artifact.getWorkflowNodeRunId() : nodeRun.getId()
        );
        fact.setSourceArtifactId(artifact.getId());
        fact.setSourceArtifactType(artifact.getType());
        fact.setSourceArtifactVersion(artifact.getVersion());
        fact.setValidFromAt(now);
        fact.setCreatedAt(now);
        try {
            factMapper.insert(fact);
        } catch (DuplicateKeyException concurrentVersion) {
            throw new IllegalStateException("project memory version conflict", concurrentVersion);
        }
    }

    private int nextVersion(Long projectId, FactIdentity identity) {
        ProjectMemoryFact latest = factMapper.selectOne(
                new LambdaQueryWrapper<ProjectMemoryFact>()
                        .eq(ProjectMemoryFact::getProjectId, projectId)
                        .eq(ProjectMemoryFact::getFactType, identity.factType())
                        .eq(ProjectMemoryFact::getFactKey, identity.factKey())
                        .orderByDesc(ProjectMemoryFact::getVersion)
                        .last("limit 1")
        );
        return latest == null ? 1 : latest.getVersion() + 1;
    }

    private void close(ProjectMemoryFact fact, LocalDateTime now) {
        factMapper.update(null, new LambdaUpdateWrapper<ProjectMemoryFact>()
                .eq(ProjectMemoryFact::getId, fact.getId())
                .eq(ProjectMemoryFact::getConflictStatus, "ACTIVE")
                .isNull(ProjectMemoryFact::getValidUntilAt)
                .set(ProjectMemoryFact::getConflictStatus, "SUPERSEDED")
                .set(ProjectMemoryFact::getValidUntilAt, now));
    }

    private Map<FactIdentity, JsonNode> extract(Artifact artifact, JsonNode content) {
        Map<FactIdentity, JsonNode> facts = new LinkedHashMap<>();
        ObjectNode artifactValue = objectMapper.createObjectNode();
        artifactValue.put("artifact_id", artifact.getId());
        artifactValue.put("artifact_type", artifact.getType());
        artifactValue.put("version", artifact.getVersion());
        artifactValue.put("content_hash", artifact.getContentHash());
        facts.put(new FactIdentity("ARTIFACT", artifact.getType()), artifactValue);

        if ("PRD".equals(artifact.getType())) {
            addObjects(facts, "REQUIREMENT", content.path("core_features"), "requirement_id");
            addTextValues(facts, "CONSTRAINT", content.path("business_boundaries"), "boundary");
            addTextValues(facts, "CONSTRAINT", content.path("non_functional_requirements"), "nfr");
        } else if ("ARCHITECTURE_DESIGN".equals(artifact.getType())) {
            addObjects(facts, "DECISION", content.path("decisions"), "decision_id");
            addObjects(facts, "CONSTRAINT", content.path("non_functional_constraints"), "constraint_id");
        } else if ("BACKEND_DESIGN".equals(artifact.getType())) {
            addObjects(facts, "ENTITY", content.path("tables"), "table_id");
            addObjects(facts, "API", content.path("apis"), "api_id");
        }
        return facts;
    }

    private void addObjects(
            Map<FactIdentity, JsonNode> facts,
            String factType,
            JsonNode values,
            String identityField
    ) {
        if (!values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            String key = value.path(identityField).asText("").trim();
            if (key.isEmpty()) {
                key = factType + ":" + ContentHash.sha256(canonical(value)).substring(0, 24);
            }
            facts.put(new FactIdentity(factType, key), value);
        }
    }

    private void addTextValues(
            Map<FactIdentity, JsonNode> facts,
            String factType,
            JsonNode values,
            String prefix
    ) {
        if (!values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) {
                continue;
            }
            String key = prefix + ":" + ContentHash.sha256(value.asText()).substring(0, 24);
            ObjectNode fact = objectMapper.createObjectNode();
            fact.put("statement", value.asText());
            facts.put(new FactIdentity(factType, key), fact);
        }
    }

    private Set<String> factTypesForNode(String nodeId) {
        return switch (nodeId) {
            case "product_manager" -> Set.of("REQUIREMENT", "CONSTRAINT", "ARTIFACT");
            case "architect" -> Set.of("REQUIREMENT", "DECISION", "CONSTRAINT", "ARTIFACT");
            default -> FACT_TYPES;
        };
    }

    private String sourceType(Artifact artifact) {
        return artifact.getSourceAgent() != null && artifact.getSourceAgent().startsWith("HUMAN_")
                ? "HUMAN_EDIT"
                : "WORKFLOW_ARTIFACT";
    }

    private Long provenanceLong(Artifact artifact, String field) {
        if (artifact.getProvenanceJson() == null || artifact.getProvenanceJson().isBlank()) {
            return null;
        }
        JsonNode value = json(artifact.getProvenanceJson()).get(field);
        return value == null || !value.canConvertToLong() ? null : value.asLong();
    }

    private ProjectMemoryFactResponse response(ProjectMemoryFact fact) {
        ObjectNode provenance = objectMapper.createObjectNode();
        provenance.put("source_type", fact.getSourceType());
        provenance.put("source_ref", fact.getSourceRef());
        provenance.put("workflow_run_id", fact.getSourceWorkflowRunId());
        provenance.put("node_run_id", fact.getSourceNodeRunId());
        provenance.put("artifact_id", fact.getSourceArtifactId());
        provenance.put("artifact_type", fact.getSourceArtifactType());
        provenance.put("artifact_version", fact.getSourceArtifactVersion());
        return new ProjectMemoryFactResponse(
                fact.getId(), fact.getProjectId(), fact.getFactType(), fact.getFactKey(),
                json(fact.getValueJson()), fact.getContentHash(), fact.getVersion(),
                fact.getConflictStatus(), provenance, fact.getValidFromAt(),
                fact.getValidUntilAt(), fact.getExpiresAt()
        );
    }

    private String canonical(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize project memory fact", exception);
        }
    }

    private Set<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String value : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}_]+")) {
            if (value.length() >= 2) {
                terms.add(value);
            }
        }
        return terms;
    }

    private int relevance(ProjectMemoryFactResponse fact, Set<String> terms) {
        if (terms.isEmpty()) {
            return 0;
        }
        String value = fact.value().toString().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (value.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid project memory JSON", exception);
        }
    }

    private record FactIdentity(String factType, String factKey) {
    }
}
