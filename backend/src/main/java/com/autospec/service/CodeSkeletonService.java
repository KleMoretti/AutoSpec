package com.autospec.service;

import com.autospec.dto.CodeGenerationResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.entity.ExportFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class CodeSkeletonService {

    private final ArtifactService artifactService;
    private final CodeGenerationJobService codeGenerationJobService;
    private final ExportFileService exportFileService;
    private final ObjectMapper objectMapper;
    private final GeneratedBundleVerificationService bundleVerificationService;

    public CodeSkeletonService(
            ArtifactService artifactService,
            CodeGenerationJobService codeGenerationJobService,
            ExportFileService exportFileService,
            ObjectMapper objectMapper,
            GeneratedBundleVerificationService bundleVerificationService
    ) {
        this.artifactService = artifactService;
        this.codeGenerationJobService = codeGenerationJobService;
        this.exportFileService = exportFileService;
        this.objectMapper = objectMapper;
        this.bundleVerificationService = bundleVerificationService;
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public CodeGenerationResponse generate(Long projectId) {
        return runJob(projectId, null, null);
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public CodeGenerationResponse generate(Long projectId, List<Artifact> deliverableArtifacts) {
        return runJob(projectId, null, deliverableArtifacts);
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public CodeGenerationResponse retry(Long projectId, Long jobId) {
        CodeGenerationJob original = codeGenerationJobService.getById(jobId);
        if (original == null || !projectId.equals(original.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Code generation job not found");
        }
        if (!"FAILED".equals(original.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed code generation jobs can be retried");
        }
        return runJob(projectId, original.getId(), null);
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public CodeGenerationResponse retry(
            Long projectId,
            Long jobId,
            List<Artifact> deliverableArtifacts
    ) {
        CodeGenerationJob original = codeGenerationJobService.getById(jobId);
        if (original == null || !projectId.equals(original.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Code generation job not found");
        }
        if (!"FAILED".equals(original.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed code generation jobs can be retried");
        }
        return runJob(projectId, original.getId(), deliverableArtifacts);
    }

    private CodeGenerationResponse runJob(
            Long projectId,
            Long retryOfJobId,
            List<Artifact> deliverableArtifacts
    ) {
        CodeGenerationJob job = new CodeGenerationJob();
        job.setProjectId(projectId);
        job.setRetryOfJobId(retryOfJobId);
        job.setStatus("RUNNING");
        codeGenerationJobService.save(job);

        try {
            List<Artifact> artifacts = deliverableArtifacts == null
                    ? artifactService.lambdaQuery()
                            .eq(Artifact::getProjectId, projectId)
                            .orderByAsc(Artifact::getId)
                            .list()
                    : List.copyOf(deliverableArtifacts);
            List<Artifact> selectedArtifacts = latestArtifacts(artifacts).values().stream().toList();
            byte[] zipBytes = zip(projectId, selectedArtifacts);
            GeneratedBundleVerificationService.VerificationReport verification =
                    bundleVerificationService.verify(zipBytes, selectedArtifacts);
            if (!verification.passed()) {
                job.setGateStatus("BLOCKED");
                job.setManifestHash(verification.manifestHash());
                job.setVerificationJson(verification.evidenceJson());
                throw new IllegalStateException("Generated bundle failed the Build/Delivery Gate");
            }
            String content = Base64.getEncoder().encodeToString(zipBytes);
            String fileName = "autospec-project-" + projectId + "-skeleton.zip";
            String manifest = manifestJson(projectId, selectedArtifacts);

            job.setStatus("SUCCEEDED");
            job.setManifest(manifest);
            job.setGateStatus("PASSED");
            job.setManifestHash(verification.manifestHash());
            job.setVerificationJson(verification.evidenceJson());
            job.setVerifiedAt(LocalDateTime.now());
            job.setCompletedAt(LocalDateTime.now());
            codeGenerationJobService.updateById(job);

            ExportFile file = new ExportFile();
            file.setProjectId(projectId);
            file.setJobId(job.getId());
            file.setFileName(fileName);
            file.setMediaType("application/zip");
            file.setEncoding("base64");
            file.setContent(content);
            exportFileService.save(file);

            return new CodeGenerationResponse("ZIP", content, fileName, "application/zip", "base64");
        } catch (RuntimeException ex) {
            job.setStatus("FAILED");
            job.setErrorMessage(ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage());
            job.setCompletedAt(LocalDateTime.now());
            codeGenerationJobService.updateById(job);
            throw ex;
        }
    }

    private byte[] zip(Long projectId, List<Artifact> artifacts) {
        Map<String, Artifact> latest = latestArtifacts(artifacts);
        JsonNode prd = artifactJson(latest, "PRD");
        JsonNode backend = artifactJson(latest, "BACKEND_DESIGN");
        JsonNode frontend = artifactJson(latest, "FRONTEND_SKELETON");
        String acceptanceTests = acceptanceTests(prd, backend, frontend);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "backend/pom.xml", backendPom());
            put(zip, "backend/src/main/java/com/generated/Application.java", springApplication());
            put(zip, "backend/src/main/java/com/generated/GeneratedContractController.java", generatedContractController(backend));
            put(zip, "backend/src/test/java/com/generated/GeneratedContractSmokeTest.java", generatedContractSmokeTest());
            put(zip, "backend/src/test/resources/autospec-acceptance-tests.json", acceptanceTests);
            put(zip, "backend/src/main/resources/application.yml", generatedApplicationYml(prd));
            put(zip, "backend/src/main/resources/schema.sql", generatedSchemaSql(backend));
            put(zip, "frontend/package.json", frontendPackageJson());
            put(zip, "frontend/index.html", frontendIndexHtml());
            put(zip, "frontend/tsconfig.json", frontendTsConfig());
            put(zip, "frontend/src/main.tsx", generatedReactMain());
            put(zip, "frontend/src/App.tsx", generatedReactApp(prd, backend, frontend));
            put(zip, "README.md", generatedReadme());
            put(zip, "ACCEPTANCE_TESTS.json", acceptanceTests);
            put(zip, "AUTOSPEC_MANIFEST.json", manifestJson(projectId, latest.values().stream().toList()));
            zip.finish();
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Code skeleton ZIP generation failed", ex);
        }
    }

    private void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String backendPom() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.5</version>
                    <relativePath/>
                  </parent>
                  <groupId>com.generated</groupId>
                  <artifactId>autospec-generated-backend</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                  <properties><java.version>17</java.version></properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-test</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                  </plugin></plugins></build>
                </project>
                """;
    }

    private String springApplication() {
        return """
                package com.generated;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }
                }
                """;
    }

    private String generatedApplicationYml(JsonNode prd) {
        return "spring:\n  application:\n    name: "
                + slug(prd.path("project_name").asText("autospec-generated-backend"))
                + "\n";
    }

    private String frontendPackageJson() {
        return """
                {
                  "name": "autospec-generated-frontend",
                  "version": "0.1.0",
                  "private": true,
                  "type": "module",
                  "scripts": {
                    "dev": "vite",
                    "build": "tsc --noEmit && vite build"
                  },
                  "dependencies": {
                    "react": "18.3.1",
                    "react-dom": "18.3.1"
                  },
                  "devDependencies": {
                    "@types/react": "18.3.17",
                    "@types/react-dom": "18.3.5",
                    "@vitejs/plugin-react": "4.3.4",
                    "typescript": "5.7.2",
                    "vite": "6.0.7"
                  }
                }
                """;
    }

    private String generatedContractController(JsonNode backend) {
        StringBuilder source = new StringBuilder("""
                package com.generated;

                import java.util.Map;
                import org.springframework.http.MediaType;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class GeneratedContractController {
                """);
        int index = 0;
        for (JsonNode api : backend.path("apis")) {
            String method = api.path("method").asText("GET").toUpperCase();
            if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                method = "GET";
            }
            String path = api.path("path").asText("/generated/api-" + index);
            String description = api.path("description").asText("Generated API contract");
            source.append("\n    @RequestMapping(path = \"")
                    .append(javaString(path))
                    .append("\", method = RequestMethod.")
                    .append(method)
                    .append(", produces = MediaType.APPLICATION_JSON_VALUE)\n")
                    .append("    public Map<String, Object> operation")
                    .append(index++)
                    .append("() {\n")
                    .append("        return Map.of(\"status\", \"READY\", \"contract\", \"")
                    .append(javaString(description))
                    .append("\");\n")
                    .append("    }\n");
        }
        source.append("}\n");
        return source.toString();
    }

    private String generatedSchemaSql(JsonNode backend) {
        StringBuilder sql = new StringBuilder("-- Generated from BACKEND_DESIGN; review before production use.\n");
        for (JsonNode table : backend.path("tables")) {
            String tableName = sqlIdentifier(table.path("name").asText("generated_table"));
            sql.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");
            int fieldIndex = 0;
            for (JsonNode field : table.path("fields")) {
                if (fieldIndex++ > 0) {
                    sql.append(",\n");
                }
                sql.append("  ")
                        .append(sqlIdentifier(field.path("name").asText("field_" + fieldIndex)))
                        .append(" ")
                        .append(sqlType(field.path("type").asText("VARCHAR")))
                        .append(field.path("nullable").asBoolean(true) ? "" : " NOT NULL");
            }
            if (fieldIndex == 0) {
                sql.append("  id BIGINT NOT NULL");
            }
            sql.append("\n);\n\n");
        }
        return sql.toString();
    }

    private String frontendIndexHtml() {
        return """
                <!doctype html>
                <html lang="en">
                  <head><meta charset="UTF-8" /><meta name="viewport" content="width=device-width, initial-scale=1.0" /><title>Generated App</title></head>
                  <body><div id="root"></div><script type="module" src="/src/main.tsx"></script></body>
                </html>
                """;
    }

    private String frontendTsConfig() {
        return """
                {
                  "compilerOptions": {
                    "target": "ES2020",
                    "lib": ["DOM", "ES2020"],
                    "module": "ESNext",
                    "moduleResolution": "Bundler",
                    "strict": true,
                    "jsx": "react-jsx",
                    "skipLibCheck": true,
                    "noEmit": true
                  },
                  "include": ["src"]
                }
                """;
    }

    private String generatedReactMain() {
        return """
                import { StrictMode } from 'react';
                import ReactDOM from 'react-dom/client';
                import App from './App';

                ReactDOM.createRoot(document.getElementById('root')!).render(
                  <StrictMode><App /></StrictMode>
                );
                """;
    }

    private String generatedReactApp(JsonNode prd, JsonNode backend, JsonNode frontend) {
        String projectName = prd.path("project_name").asText("AutoSpec Generated Project");
        return """
                interface PageContract { name: string; purpose: string; components?: string[] }
                interface ApiContract { method: string; path: string; description: string }

                const projectName: string = %s;
                const pages: PageContract[] = %s;
                const apis: ApiContract[] = %s;

                export default function App() {
                  return (
                    <main style={{ maxWidth: 960, margin: '0 auto', padding: 32, fontFamily: 'system-ui' }}>
                      <h1>{projectName}</h1>
                      <p>This compilable scaffold is generated from the approved PRD, API, data, and page contracts.</p>
                      <h2>Pages</h2>
                      {pages.map((page) => (
                        <section key={page.name} style={{ border: '1px solid #ddd', borderRadius: 8, padding: 16, marginBottom: 12 }}>
                          <h3>{page.name}</h3><p>{page.purpose}</p>
                          <small>{(page.components ?? []).join(' / ')}</small>
                        </section>
                      ))}
                      <h2>API contract</h2>
                      <ul>{apis.map((api) => <li key={`${api.method}:${api.path}`}><code>{api.method} {api.path}</code> - {api.description}</li>)}</ul>
                    </main>
                  );
                }
                """.formatted(
                jsonString(projectName),
                frontend.path("pages").isArray() ? frontend.path("pages").toString() : "[]",
                backend.path("apis").isArray() ? backend.path("apis").toString() : "[]"
        );
    }

    private String generatedReadme() {
        return """
                # AutoSpec generated scaffold

                This runnable prototype is generated from approved structured artifacts. API operations return typed
                mock responses and are covered by a generated contract smoke test. Replace mock domain behavior before
                production deployment.

                - Backend verification: `cd backend && mvn test`
                - Frontend verification: `cd frontend && npm install && npm run build`
                """;
    }

    private String generatedContractSmokeTest() {
        return """
                package com.generated;

                import java.lang.reflect.Method;
                import java.io.InputStream;
                import java.util.Map;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class GeneratedContractSmokeTest {
                    @Test
                    void everyGeneratedOperationReturnsReadyMockResponse() throws Exception {
                        GeneratedContractController controller = new GeneratedContractController();
                        int operations = 0;
                        for (Method method : GeneratedContractController.class.getDeclaredMethods()) {
                            if (!method.getName().startsWith("operation")) continue;
                            operations++;
                            Object response = method.invoke(controller);
                            assertTrue(response instanceof Map<?, ?>);
                            assertEquals("READY", ((Map<?, ?>) response).get("status"));
                        }
                        assertTrue(operations > 0);
                    }

                    @Test
                    void everyMustRequirementHasAcceptanceApiAndUiEvidence() throws Exception {
                        try (InputStream input = getClass().getResourceAsStream(
                                "/autospec-acceptance-tests.json")) {
                            assertNotNull(input);
                            JsonNode requirements = new ObjectMapper().readTree(input).path("requirements");
                            assertFalse(requirements.isEmpty());
                            for (JsonNode requirement : requirements) {
                                if (!"MUST".equals(requirement.path("priority").asText())) continue;
                                assertFalse(requirement.path("acceptance_ids").isEmpty());
                                assertFalse(requirement.path("api_ids").isEmpty());
                                assertFalse(requirement.path("ui_ids").isEmpty());
                            }
                        }
                    }
                }
                """;
    }

    private String acceptanceTests(JsonNode prd, JsonNode backend, JsonNode frontend) {
        try {
            var root = objectMapper.createObjectNode();
            var requirements = root.putArray("requirements");
            for (JsonNode feature : prd.path("core_features")) {
                String requirementId = feature.path("requirement_id").asText("");
                if (requirementId.isBlank()) {
                    requirementId = "REQ-" + sha256(
                            feature.path("name").asText() + "|" + feature.path("description").asText()
                    ).substring(0, 12).toUpperCase();
                }
                var requirement = requirements.addObject();
                requirement.put("requirement_id", requirementId);
                requirement.put("priority", feature.path("priority").asText("SHOULD"));
                var acceptanceIds = requirement.putArray("acceptance_ids");
                for (JsonNode story : prd.path("user_stories")) {
                    for (JsonNode criterion : story.path("acceptance_criteria")) {
                        JsonNode refs = criterion.isObject()
                                ? criterion.path("requirement_refs")
                                : story.path("requirement_refs");
                        if (contains(refs, requirementId)) {
                            String id = criterion.isObject()
                                    ? criterion.path("acceptance_id").asText("")
                                    : "";
                            acceptanceIds.add(id.isBlank()
                                    ? "AC-" + sha256(criterion.asText()).substring(0, 12).toUpperCase()
                                    : id);
                        }
                    }
                }
                var apiIds = requirement.putArray("api_ids");
                for (JsonNode api : backend.path("apis")) {
                    if (contains(api.path("requirement_refs"), requirementId)) {
                        apiIds.add(api.path("api_id").asText(
                                "API-" + sha256(api.path("method").asText() + api.path("path").asText())
                                        .substring(0, 12).toUpperCase()
                        ));
                    }
                }
                var uiIds = requirement.putArray("ui_ids");
                for (String collection : List.of("routes", "pages", "components", "api_bindings")) {
                    for (JsonNode item : frontend.path(collection)) {
                        if (contains(item.path("requirement_refs"), requirementId)) {
                            String id = firstText(item, "route_id", "page_id", "component_id", "binding_id");
                            if (id != null) uiIds.add(id);
                        }
                    }
                }
            }
            if (requirements.isEmpty()) {
                var legacy = requirements.addObject();
                legacy.put("requirement_id", "REQ-LEGACY");
                legacy.put("priority", "SHOULD");
                legacy.putArray("acceptance_ids");
                legacy.putArray("api_ids");
                legacy.putArray("ui_ids");
            }
            return root.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Acceptance test generation failed", exception);
        }
    }

    private boolean contains(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) return true;
        }
        return false;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private Map<String, Artifact> latestArtifacts(List<Artifact> artifacts) {
        Map<String, Artifact> latest = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            latest.merge(artifact.getType(), artifact, (left, right) ->
                    Comparator.comparingInt(this::artifactVersion)
                            .thenComparing(Artifact::getId, Comparator.nullsFirst(Long::compareTo))
                            .compare(left, right) >= 0 ? left : right
            );
        }
        return latest;
    }

    private JsonNode artifactJson(Map<String, Artifact> artifacts, String type) {
        Artifact artifact = artifacts.get(type);
        if (artifact == null || artifact.getContent() == null || artifact.getContent().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(artifact.getContent());
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid " + type + " artifact JSON", ex);
        }
    }

    private String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                + "\"";
    }

    private String javaString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String sqlIdentifier(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase();
        return normalized.isBlank() ? "generated_item" : normalized;
    }

    private String sqlType(String value) {
        String normalized = value.toUpperCase();
        if (normalized.contains("BIGINT") || normalized.equals("LONG")) return "BIGINT";
        if (normalized.contains("INT")) return "INTEGER";
        if (normalized.contains("BOOL")) return "BOOLEAN";
        if (normalized.contains("DATE") || normalized.contains("TIME")) return "TIMESTAMP";
        if (normalized.contains("DECIMAL") || normalized.contains("NUMERIC")) return "DECIMAL(19, 2)";
        if (normalized.contains("TEXT")) return "TEXT";
        return "VARCHAR(255)";
    }

    private String slug(String value) {
        String normalized = value.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return normalized.isBlank() ? "autospec-generated-backend" : normalized;
    }

    private int artifactVersion(Artifact artifact) {
        return artifact.getVersion() == null ? 0 : artifact.getVersion();
    }

    private String manifestJson(Long projectId, List<Artifact> artifacts) {
        try {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("project_id", projectId);
            manifest.put("generated_by", "AutoSpec V5");
            manifest.put("generator_version", "bundle-v2");
            manifest.put("artifacts", artifacts.stream().map(artifact -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("artifact_id", artifact.getId());
                value.put("type", artifact.getType());
                value.put("version", artifact.getVersion());
                value.put("title", artifact.getTitle());
                value.put("content_hash", artifact.getContentHash() == null
                        ? sha256(artifact.getContent())
                        : artifact.getContentHash());
                value.put("workflow_node_run_id", artifact.getWorkflowNodeRunId());
                value.put("schema_version", artifact.getSchemaVersion());
                value.put("prompt_key", artifact.getPromptKey());
                value.put("prompt_version", artifact.getPromptVersion());
                value.put("model_provider", artifact.getModelProvider());
                value.put("model_name", artifact.getModelName());
                return value;
            }).toList());
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception ex) {
            throw new IllegalStateException("Manifest generation failed", ex);
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
