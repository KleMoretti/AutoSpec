package com.autospec.service;

import com.autospec.entity.Artifact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MarkdownExportService {

    private static final Map<String, Integer> SEVERITY_ORDER = Map.of(
            "HIGH", 0,
            "MEDIUM", 1,
            "LOW", 2
    );

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public MarkdownExportService(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    public String exportProject(Long projectId) {
        Map<String, Artifact> artifacts = artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .list()
                .stream()
                .collect(Collectors.toMap(
                        Artifact::getType,
                        Function.identity(),
                        (first, second) -> version(first) >= version(second) ? first : second
                ));
        Artifact prd = requiredArtifact(artifacts, "PRD");
        Artifact backendDesign = requiredArtifact(artifacts, "BACKEND_DESIGN");
        Artifact reviewReport = requiredArtifact(artifacts, "REVIEW_REPORT");
        Artifact architectureDesign = artifacts.get("ARCHITECTURE_DESIGN");
        Artifact frontendSkeleton = artifacts.get("FRONTEND_SKELETON");
        return render(
                prd.getContent(),
                architectureDesign == null ? null : architectureDesign.getContent(),
                backendDesign.getContent(),
                frontendSkeleton == null ? null : frontendSkeleton.getContent(),
                reviewReport.getContent()
        );
    }

    public String render(String prdJson, String backendJson, String reviewJson) {
        return render(prdJson, null, backendJson, null, reviewJson);
    }

    public String render(
            String prdJson,
            String architectureJson,
            String backendJson,
            String frontendJson,
            String reviewJson
    ) {
        try {
            JsonNode prd = objectMapper.readTree(prdJson);
            JsonNode backend = objectMapper.readTree(backendJson);
            JsonNode review = objectMapper.readTree(reviewJson);

            StringBuilder markdown = new StringBuilder();
            markdown.append("# ").append(text(prd, "project_name")).append("\n\n");
            appendPrd(markdown, prd);
            if (architectureJson != null && !architectureJson.isBlank()) {
                appendArchitectureDesign(markdown, objectMapper.readTree(architectureJson));
            }
            appendBackendDesign(markdown, backend);
            if (frontendJson != null && !frontendJson.isBlank()) {
                appendFrontendSkeleton(markdown, objectMapper.readTree(frontendJson));
            }
            appendReview(markdown, review);
            return markdown.toString();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to render markdown", ex);
        }
    }

    private Artifact requiredArtifact(Map<String, Artifact> artifacts, String type) {
        Artifact artifact = artifacts.get(type);
        if (artifact == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Missing artifact: " + type);
        }
        return artifact;
    }

    private void appendPrd(StringBuilder markdown, JsonNode prd) {
        markdown.append("## PRD\n\n");
        appendList(markdown, "Target Users", prd.path("target_users"));
        appendFeatures(markdown, prd.path("core_features"));
        appendUserStories(markdown, prd.path("user_stories"));
        appendList(markdown, "Business Boundaries", prd.path("business_boundaries"));
        appendList(markdown, "Non-Functional Requirements", prd.path("non_functional_requirements"));
        appendList(markdown, "Risks", prd.path("risks"));
    }

    private void appendFeatures(StringBuilder markdown, JsonNode features) {
        markdown.append("### Core Features\n\n");
        markdown.append("| Priority | Feature | Description |\n");
        markdown.append("| --- | --- | --- |\n");
        for (JsonNode feature : features) {
            markdown.append("| ")
                    .append(cell(feature.path("priority").asText()))
                    .append(" | ")
                    .append(cell(feature.path("name").asText()))
                    .append(" | ")
                    .append(cell(feature.path("description").asText()))
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendUserStories(StringBuilder markdown, JsonNode stories) {
        markdown.append("### User Stories\n\n");
        markdown.append("| Role | Goal | Benefit | Acceptance Criteria |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        for (JsonNode story : stories) {
            markdown.append("| ")
                    .append(cell(story.path("role").asText()))
                    .append(" | ")
                    .append(cell(story.path("goal").asText()))
                    .append(" | ")
                    .append(cell(story.path("benefit").asText()))
                    .append(" | ")
                    .append(cell(joinArray(story.path("acceptance_criteria"))))
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendBackendDesign(StringBuilder markdown, JsonNode backend) {
        markdown.append("## Database Design\n\n");
        for (JsonNode table : backend.path("tables")) {
            markdown.append("### Table: ").append(text(table, "name")).append("\n\n");
            markdown.append(text(table, "description")).append("\n\n");
            markdown.append("| Field | Type | Nullable | Description |\n");
            markdown.append("| --- | --- | --- | --- |\n");
            for (JsonNode field : table.path("fields")) {
                markdown.append("| ")
                        .append(cell(field.path("name").asText()))
                        .append(" | ")
                        .append(cell(field.path("type").asText()))
                        .append(" | ")
                        .append(field.path("nullable").asBoolean() ? "Yes" : "No")
                        .append(" | ")
                        .append(cell(field.path("description").asText()))
                        .append(" |\n");
            }
            markdown.append("\n");
        }

        markdown.append("## API Design\n\n");
        for (JsonNode api : backend.path("apis")) {
            markdown.append("### ")
                    .append(text(api, "method"))
                    .append(" ")
                    .append(text(api, "path"))
                    .append("\n\n");
            markdown.append(text(api, "description")).append("\n\n");
            markdown.append("- Auth required: ").append(api.path("auth_required").asBoolean() ? "Yes" : "No").append("\n");
            markdown.append("- Required roles: ").append(joinArray(api.path("required_roles"))).append("\n\n");
            appendParams(markdown, "Request Parameters", api.path("request_params"), true);
            appendParams(markdown, "Response Fields", api.path("response_fields"), false);
        }
    }

    private void appendArchitectureDesign(StringBuilder markdown, JsonNode architecture) {
        markdown.append("## Architecture Design\n\n");
        markdown.append(architecture.path("system_context").asText("")).append("\n\n");

        markdown.append("### Modules\n\n");
        markdown.append("| Module | Responsibility | Depends On |\n");
        markdown.append("| --- | --- | --- |\n");
        for (JsonNode module : architecture.path("modules")) {
            markdown.append("| ")
                    .append(cell(module.path("name").asText()))
                    .append(" | ")
                    .append(cell(module.path("responsibility").asText()))
                    .append(" | ")
                    .append(cell(joinArray(module.path("depends_on"))))
                    .append(" |\n");
        }
        markdown.append("\n");

        markdown.append("### Decisions\n\n");
        for (JsonNode decision : architecture.path("decisions")) {
            markdown.append("- **")
                    .append(decision.path("title").asText())
                    .append("**: ")
                    .append(decision.path("decision").asText())
                    .append("\n");
        }
        markdown.append("\n");
        appendList(markdown, "Integration Risks", architecture.path("integration_risks"));
    }

    private void appendFrontendSkeleton(StringBuilder markdown, JsonNode frontend) {
        markdown.append("## Frontend Skeleton\n\n");

        markdown.append("### Routes\n\n");
        markdown.append("| Path | Page |\n");
        markdown.append("| --- | --- |\n");
        for (JsonNode route : frontend.path("routes")) {
            markdown.append("| ")
                    .append(cell(route.path("path").asText()))
                    .append(" | ")
                    .append(cell(route.path("page").asText()))
                    .append(" |\n");
        }
        markdown.append("\n");

        markdown.append("### Pages\n\n");
        markdown.append("| Page | Purpose | Components |\n");
        markdown.append("| --- | --- | --- |\n");
        for (JsonNode page : frontend.path("pages")) {
            markdown.append("| ")
                    .append(cell(page.path("name").asText()))
                    .append(" | ")
                    .append(cell(page.path("purpose").asText()))
                    .append(" | ")
                    .append(cell(joinArray(page.path("components"))))
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendParams(StringBuilder markdown, String title, JsonNode fields, boolean includeRequired) {
        markdown.append("#### ").append(title).append("\n\n");
        if (!fields.isArray() || fields.isEmpty()) {
            markdown.append("None.\n\n");
            return;
        }
        markdown.append(includeRequired
                ? "| Name | Type | Required | Description |\n| --- | --- | --- | --- |\n"
                : "| Name | Type | Description |\n| --- | --- | --- |\n");
        for (JsonNode field : fields) {
            markdown.append("| ")
                    .append(cell(field.path("name").asText()))
                    .append(" | ")
                    .append(cell(field.path("type").asText()))
                    .append(" | ");
            if (includeRequired) {
                markdown.append(field.path("required").asBoolean() ? "Yes" : "No").append(" | ");
            }
            markdown.append(cell(field.path("description").asText())).append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendReview(StringBuilder markdown, JsonNode review) {
        markdown.append("## Review Report\n\n");
        markdown.append("- Score: ").append(review.path("score").asInt()).append("\n\n");
        markdown.append("| Severity | Type | Description | Suggestion |\n");
        markdown.append("| --- | --- | --- | --- |\n");
        List<JsonNode> issues = new java.util.ArrayList<>();
        for (JsonNode issue : review.path("issues")) {
            issues.add(issue);
        }
        issues.sort(Comparator.comparingInt(issue -> SEVERITY_ORDER.getOrDefault(
                issue.path("severity").asText().toUpperCase(),
                99
        )));
        for (JsonNode issue : issues) {
            markdown.append("| ")
                    .append(cell(issue.path("severity").asText()))
                    .append(" | ")
                    .append(cell(issue.path("issue_type").asText()))
                    .append(" | ")
                    .append(cell(issue.path("description").asText()))
                    .append(" | ")
                    .append(cell(issue.path("suggestion").asText()))
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendList(StringBuilder markdown, String title, JsonNode values) {
        markdown.append("### ").append(title).append("\n\n");
        if (!values.isArray() || values.isEmpty()) {
            markdown.append("- None\n\n");
            return;
        }
        for (JsonNode value : values) {
            markdown.append("- ").append(value.asText()).append("\n");
        }
        markdown.append("\n");
    }

    private String text(JsonNode node, String fieldName) {
        return node.path(fieldName).asText("");
    }

    private String joinArray(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return "None";
        }
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode value : values) {
            result.add(value.asText());
        }
        return String.join("<br>", result);
    }

    private String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", "<br>");
    }

    private int version(Artifact artifact) {
        return artifact.getVersion() == null ? 0 : artifact.getVersion();
    }
}
