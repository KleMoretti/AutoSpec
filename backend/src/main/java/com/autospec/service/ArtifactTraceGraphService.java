package com.autospec.service;

import com.autospec.dto.ArtifactTraceGraphResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.ArtifactComponent;
import com.autospec.entity.ArtifactTraceEdge;
import com.autospec.mapper.ArtifactComponentMapper;
import com.autospec.mapper.ArtifactTraceEdgeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ArtifactTraceGraphService {
    private final ArtifactComponentMapper componentMapper;
    private final ArtifactTraceEdgeMapper edgeMapper;
    private final ObjectMapper objectMapper;

    public ArtifactTraceGraphService(
            ArtifactComponentMapper componentMapper,
            ArtifactTraceEdgeMapper edgeMapper,
            ObjectMapper objectMapper
    ) {
        this.componentMapper = componentMapper;
        this.edgeMapper = edgeMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void project(Artifact artifact) {
        Long runId = null;
        if (artifact != null && artifact.getParentArtifactId() != null) {
            ArtifactComponent parent = componentMapper.selectOne(
                    new LambdaQueryWrapper<ArtifactComponent>()
                            .eq(ArtifactComponent::getArtifactId, artifact.getParentArtifactId())
                            .orderByDesc(ArtifactComponent::getId)
                            .last("limit 1")
            );
            runId = parent == null ? null : parent.getWorkflowRunId();
        }
        project(artifact, runId);
    }

    @Transactional
    public void project(Artifact artifact, Long workflowRunId) {
        if (artifact == null || artifact.getId() == null || artifact.getContent() == null) {
            return;
        }
        componentMapper.delete(new LambdaUpdateWrapper<ArtifactComponent>()
                .eq(ArtifactComponent::getArtifactId, artifact.getId()));
        edgeMapper.delete(new LambdaUpdateWrapper<ArtifactTraceEdge>()
                .eq(ArtifactTraceEdge::getSourceArtifactId, artifact.getId()));
        JsonNode root = json(artifact.getContent());
        switch (artifact.getType()) {
            case "PRD" -> projectPrd(artifact, workflowRunId, root);
            case "ARCHITECTURE_DESIGN" -> projectArchitecture(artifact, workflowRunId, root);
            case "BACKEND_DESIGN" -> projectBackend(artifact, workflowRunId, root);
            case "FRONTEND_SKELETON" -> projectFrontend(artifact, workflowRunId, root);
            default -> {
                // Reports refer to the graph but do not define canonical components.
            }
        }
    }

    public ArtifactTraceGraphResponse graph(Long projectId, Long requestedRunId) {
        Long runId = requestedRunId;
        if (runId == null) {
            ArtifactComponent latest = componentMapper.selectOne(
                    new LambdaQueryWrapper<ArtifactComponent>()
                            .eq(ArtifactComponent::getProjectId, projectId)
                            .isNotNull(ArtifactComponent::getWorkflowRunId)
                            .orderByDesc(ArtifactComponent::getWorkflowRunId)
                            .orderByDesc(ArtifactComponent::getId)
                            .last("limit 1")
            );
            runId = latest == null ? null : latest.getWorkflowRunId();
        }
        LambdaQueryWrapper<ArtifactComponent> componentQuery =
                new LambdaQueryWrapper<ArtifactComponent>()
                        .eq(ArtifactComponent::getProjectId, projectId)
                        .orderByAsc(ArtifactComponent::getId);
        LambdaQueryWrapper<ArtifactTraceEdge> edgeQuery =
                new LambdaQueryWrapper<ArtifactTraceEdge>()
                        .eq(ArtifactTraceEdge::getProjectId, projectId)
                        .orderByAsc(ArtifactTraceEdge::getId);
        if (runId == null) {
            componentQuery.isNull(ArtifactComponent::getWorkflowRunId);
            edgeQuery.isNull(ArtifactTraceEdge::getWorkflowRunId);
        } else {
            componentQuery.eq(ArtifactComponent::getWorkflowRunId, runId);
            edgeQuery.eq(ArtifactTraceEdge::getWorkflowRunId, runId);
        }
        return new ArtifactTraceGraphResponse(
                runId,
                componentMapper.selectList(componentQuery).stream()
                        .map(ArtifactTraceGraphResponse.Component::from)
                        .toList(),
                edgeMapper.selectList(edgeQuery).stream()
                        .map(ArtifactTraceGraphResponse.Edge::from)
                        .toList()
        );
    }

    private void projectPrd(Artifact artifact, Long runId, JsonNode root) {
        forEach(root.path("core_features"), (feature, index) -> {
            String key = key(feature, "requirement_id", "REQ", feature.path("name").asText());
            addComponent(artifact, runId, key, "REQUIREMENT",
                    feature.path("name").asText(key), "$.core_features[" + index + "]", feature);
        });
        forEach(root.path("user_stories"), (story, storyIndex) -> {
            String storyKey = key(story, "story_id", "STORY", story.path("goal").asText());
            addComponent(artifact, runId, storyKey, "STORY",
                    story.path("goal").asText(storyKey), "$.user_stories[" + storyIndex + "]", story);
            addRequirementEdges(artifact, runId, storyKey, story.path("requirement_refs"), "SATISFIES");
            forEach(story.path("acceptance_criteria"), (criterion, criterionIndex) -> {
                JsonNode criterionNode = criterion.isTextual()
                        ? objectMapper.createObjectNode().put("criterion", criterion.asText())
                        : criterion;
                String criterionKey = key(
                        criterionNode,
                        "acceptance_id",
                        "AC",
                        criterionNode.path("criterion").asText()
                );
                String path = "$.user_stories[" + storyIndex + "].acceptance_criteria[" + criterionIndex + "]";
                addComponent(artifact, runId, criterionKey, "ACCEPTANCE",
                        criterionNode.path("criterion").asText(criterionKey), path, criterionNode);
                addEdge(artifact, runId, criterionKey, storyKey, "VERIFIES_STORY");
                JsonNode refs = criterionNode.path("requirement_refs").isArray()
                        ? criterionNode.path("requirement_refs")
                        : story.path("requirement_refs");
                addRequirementEdges(artifact, runId, criterionKey, refs, "VERIFIES");
            });
        });
    }

    private void projectArchitecture(Artifact artifact, Long runId, JsonNode root) {
        Map<String, String> moduleIds = new LinkedHashMap<>();
        forEach(root.path("modules"), (module, index) -> {
            String moduleKey = key(module, "module_id", "MOD", module.path("name").asText());
            moduleIds.put(module.path("name").asText(), moduleKey);
            addComponent(artifact, runId, moduleKey, "MODULE", module.path("name").asText(moduleKey),
                    "$.modules[" + index + "]", module);
            addRequirementEdges(artifact, runId, moduleKey, module.path("requirement_refs"), "IMPLEMENTS");
        });
        forEach(root.path("modules"), (module, index) -> {
            String moduleKey = key(module, "module_id", "MOD", module.path("name").asText());
            Set<String> dependencies = new LinkedHashSet<>();
            module.path("depends_on").forEach(value -> dependencies.add(value.asText()));
            for (String dependency : dependencies) {
                String dependencyKey = moduleIds.get(dependency);
                if (dependencyKey != null) {
                    addEdge(artifact, runId, moduleKey, dependencyKey, "DEPENDS_ON");
                }
            }
        });
        projectArray(artifact, runId, root.path("decisions"), "decision_id", "ADR", "DECISION", "title", "decisions");
        projectArray(artifact, runId, root.path("non_functional_constraints"),
                "constraint_id", "NFR", "CONSTRAINT", "requirement", "non_functional_constraints");
    }

    private void projectBackend(Artifact artifact, Long runId, JsonNode root) {
        forEach(root.path("tables"), (table, tableIndex) -> {
            String tableKey = key(table, "table_id", "TABLE", table.path("name").asText());
            addComponent(artifact, runId, tableKey, "TABLE", table.path("name").asText(tableKey),
                    "$.tables[" + tableIndex + "]", table);
            addRequirementEdges(artifact, runId, tableKey, table.path("requirement_refs"), "IMPLEMENTS");
            forEach(table.path("fields"), (field, fieldIndex) -> {
                String fieldKey = key(field, "field_id", "FIELD",
                        tableKey + ":" + field.path("name").asText());
                addComponent(artifact, runId, fieldKey, "FIELD",
                        table.path("name").asText() + "." + field.path("name").asText(),
                        "$.tables[" + tableIndex + "].fields[" + fieldIndex + "]", field);
                addEdge(artifact, runId, fieldKey, tableKey, "BELONGS_TO");
                JsonNode refs = field.path("requirement_refs").isArray()
                        ? field.path("requirement_refs")
                        : table.path("requirement_refs");
                addRequirementEdges(artifact, runId, fieldKey, refs, "IMPLEMENTS");
            });
        });
        projectArray(artifact, runId, root.path("apis"), "api_id", "API", "API", "path", "apis");
    }

    private void projectFrontend(Artifact artifact, Long runId, JsonNode root) {
        Map<String, String> pageIds = collectIds(root.path("pages"), "name", "page_id", "PAGE");
        Map<String, String> componentIds = collectIds(
                root.path("components"), "name", "component_id", "COMP"
        );
        forEach(root.path("routes"), (route, index) -> {
            String routeKey = key(route, "route_id", "ROUTE",
                    route.path("path").asText() + ":" + route.path("page").asText());
            addComponent(artifact, runId, routeKey, "ROUTE", route.path("path").asText(routeKey),
                    "$.routes[" + index + "]", route);
            addRequirementEdges(artifact, runId, routeKey, route.path("requirement_refs"), "IMPLEMENTS");
            String pageKey = pageIds.get(route.path("page").asText());
            if (pageKey != null) {
                addEdge(artifact, runId, routeKey, pageKey, "ROUTES_TO");
            }
        });
        forEach(root.path("pages"), (page, index) -> {
            String pageKey = key(page, "page_id", "PAGE", page.path("name").asText());
            addComponent(artifact, runId, pageKey, "PAGE", page.path("name").asText(pageKey),
                    "$.pages[" + index + "]", page);
            addRequirementEdges(artifact, runId, pageKey, page.path("requirement_refs"), "IMPLEMENTS");
            Set<String> components = new LinkedHashSet<>();
            page.path("components").forEach(value -> components.add(value.asText()));
            for (String component : components) {
                String componentKey = componentIds.get(component);
                if (componentKey != null) {
                    addEdge(artifact, runId, pageKey, componentKey, "CONTAINS");
                }
            }
        });
        projectArray(artifact, runId, root.path("components"),
                "component_id", "COMP", "UI_COMPONENT", "name", "components");
        forEach(root.path("api_bindings"), (binding, index) -> {
            String bindingKey = key(binding, "binding_id", "BIND",
                    binding.path("method").asText() + ":" + binding.path("path").asText());
            addComponent(artifact, runId, bindingKey, "API_BINDING",
                    binding.path("method").asText() + " " + binding.path("path").asText(),
                    "$.api_bindings[" + index + "]", binding);
            addRequirementEdges(artifact, runId, bindingKey, binding.path("requirement_refs"), "IMPLEMENTS");
            String apiKey = text(binding, "backend_api_id");
            if (apiKey != null) {
                addEdge(artifact, runId, bindingKey, apiKey, "CONSUMES");
            }
            String componentKey = componentIds.get(binding.path("consumer").asText());
            if (componentKey != null) {
                addEdge(artifact, runId, componentKey, bindingKey, "USES_BINDING");
            }
        });
    }

    private Map<String, String> collectIds(
            JsonNode values,
            String nameField,
            String idField,
            String prefix
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        forEach(values, (value, index) -> result.put(
                value.path(nameField).asText(),
                key(value, idField, prefix, value.path(nameField).asText())
        ));
        return result;
    }

    private void projectArray(
            Artifact artifact,
            Long runId,
            JsonNode values,
            String idField,
            String prefix,
            String componentType,
            String labelField,
            String pathField
    ) {
        forEach(values, (value, index) -> {
            String key = key(value, idField, prefix, value.path(labelField).asText());
            addComponent(artifact, runId, key, componentType,
                    value.path(labelField).asText(key), "$." + pathField + "[" + index + "]", value);
            addRequirementEdges(artifact, runId, key, value.path("requirement_refs"), "IMPLEMENTS");
        });
    }

    private void addRequirementEdges(
            Artifact artifact,
            Long runId,
            String from,
            JsonNode refs,
            String relation
    ) {
        if (!refs.isArray()) {
            return;
        }
        Set<String> unique = new LinkedHashSet<>();
        refs.forEach(ref -> {
            if (ref.isTextual() && !ref.asText().isBlank()) {
                unique.add(ref.asText());
            }
        });
        for (String ref : unique) {
            addEdge(artifact, runId, from, ref, relation);
        }
    }

    private void addComponent(
            Artifact artifact,
            Long runId,
            String key,
            String type,
            String label,
            String path,
            JsonNode content
    ) {
        ArtifactComponent component = new ArtifactComponent();
        component.setProjectId(artifact.getProjectId());
        component.setWorkflowRunId(runId);
        component.setArtifactId(artifact.getId());
        component.setArtifactType(artifact.getType());
        component.setComponentKey(key);
        component.setComponentType(type);
        component.setDisplayName(label == null || label.isBlank() ? key : label);
        component.setJsonPath(path);
        component.setContentHash(hash(content.toString()));
        component.setCreatedAt(LocalDateTime.now());
        componentMapper.insert(component);
    }

    private void addEdge(Artifact artifact, Long runId, String from, String to, String relation) {
        ArtifactTraceEdge edge = new ArtifactTraceEdge();
        edge.setProjectId(artifact.getProjectId());
        edge.setWorkflowRunId(runId);
        edge.setSourceArtifactId(artifact.getId());
        edge.setFromComponentKey(from);
        edge.setToComponentKey(to);
        edge.setRelationType(relation);
        edge.setCreatedAt(LocalDateTime.now());
        edgeMapper.insert(edge);
    }

    private String key(JsonNode node, String field, String prefix, String material) {
        String explicit = text(node, field);
        return explicit == null ? prefix + "-" + hash(material).substring(0, 12).toUpperCase() : explicit;
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private JsonNode json(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Artifact trace projection requires valid JSON", exception);
        }
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void forEach(JsonNode values, IndexedConsumer consumer) {
        if (!values.isArray()) {
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            consumer.accept(values.get(index), index);
        }
    }

    @FunctionalInterface
    private interface IndexedConsumer {
        void accept(JsonNode value, int index);
    }
}
