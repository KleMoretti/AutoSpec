package com.autospec.service;

import com.autospec.dto.CodeGenerationResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.entity.ExportFile;
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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class CodeSkeletonService {

    private final ArtifactService artifactService;
    private final CodeGenerationJobService codeGenerationJobService;
    private final ExportFileService exportFileService;
    private final ObjectMapper objectMapper;

    public CodeSkeletonService(
            ArtifactService artifactService,
            CodeGenerationJobService codeGenerationJobService,
            ExportFileService exportFileService,
            ObjectMapper objectMapper
    ) {
        this.artifactService = artifactService;
        this.codeGenerationJobService = codeGenerationJobService;
        this.exportFileService = exportFileService;
        this.objectMapper = objectMapper;
    }

    @Transactional(noRollbackFor = RuntimeException.class)
    public CodeGenerationResponse generate(Long projectId) {
        return runJob(projectId, null);
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
        return runJob(projectId, original.getId());
    }

    private CodeGenerationResponse runJob(Long projectId, Long retryOfJobId) {
        CodeGenerationJob job = new CodeGenerationJob();
        job.setProjectId(projectId);
        job.setRetryOfJobId(retryOfJobId);
        job.setStatus("RUNNING");
        codeGenerationJobService.save(job);

        try {
            List<Artifact> artifacts = artifactService.lambdaQuery()
                    .eq(Artifact::getProjectId, projectId)
                    .orderByAsc(Artifact::getId)
                    .list();
            byte[] zipBytes = zip(projectId, artifacts);
            String content = Base64.getEncoder().encodeToString(zipBytes);
            String fileName = "autospec-project-" + projectId + "-skeleton.zip";
            String manifest = manifestJson(projectId, artifacts);

            job.setStatus("SUCCEEDED");
            job.setManifest(manifest);
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
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "backend/pom.xml", backendPom());
            put(zip, "backend/src/main/java/com/generated/Application.java", springApplication());
            put(zip, "backend/src/main/resources/application.yml", generatedApplicationYml());
            put(zip, "frontend/package.json", frontendPackageJson());
            put(zip, "frontend/src/App.tsx", generatedReactApp());
            put(zip, "AUTOSPEC_MANIFEST.json", manifestJson(projectId, artifacts));
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
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.generated</groupId>
                  <artifactId>autospec-generated-backend</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                  <properties><java.version>17</java.version></properties>
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

    private String generatedApplicationYml() {
        return """
                spring:
                  application:
                    name: autospec-generated-backend
                """;
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
                    "build": "vite build"
                  },
                  "dependencies": {
                    "@vitejs/plugin-react": "latest",
                    "vite": "latest",
                    "typescript": "latest",
                    "react": "latest",
                    "react-dom": "latest"
                  }
                }
                """;
    }

    private String generatedReactApp() {
        return """
                import React from 'react';

                export default function App() {
                  return <main><h1>AutoSpec Generated Skeleton</h1></main>;
                }
                """;
    }

    private String manifestJson(Long projectId, List<Artifact> artifacts) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "projectId", projectId,
                    "generatedBy", "AutoSpec V3",
                    "artifacts", artifacts.stream()
                            .map(artifact -> java.util.Map.of(
                                    "type", artifact.getType(),
                                    "version", artifact.getVersion(),
                                    "title", artifact.getTitle()
                            ))
                            .toList()
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("Manifest generation failed", ex);
        }
    }
}
