package com.autospec;

import com.autospec.dto.CodeGenerationResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.entity.ExportFile;
import com.autospec.entity.Project;
import com.autospec.service.ArtifactService;
import com.autospec.service.CodeGenerationJobService;
import com.autospec.service.CodeSkeletonService;
import com.autospec.service.ExportFileService;
import com.autospec.service.ProjectService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.time.LocalDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class CodeSkeletonServiceTest {

    @Autowired
    private CodeSkeletonService codeSkeletonService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private CodeGenerationJobService codeGenerationJobService;

    @Autowired
    private ExportFileService exportFileService;

    @SpyBean
    private ObjectMapper objectMapper;

    @Test
    void exportsSpringAndReactSkeletonZip() throws Exception {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("Code Export Project");
        project.setOriginalRequirement("Export code skeleton.");
        project.setStatus("COMPLETED");
        projectService.save(project);

        saveArtifact(project.getId(), "PRD", "{\"project_name\":\"Code Export\"}");
        saveArtifact(project.getId(), "BACKEND_DESIGN", "{\"apis\":[]}");
        saveArtifact(project.getId(), "FRONTEND_SKELETON", "{\"routes\":[]}");

        CodeGenerationResponse response = codeSkeletonService.generate(project.getId());

        assertThat(response.fileName()).endsWith(".zip");
        byte[] bytes = Base64.getDecoder().decode(response.content());
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            List<String> names = new ArrayList<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
            assertThat(names).contains(
                    "backend/pom.xml",
                    "backend/src/main/java/com/generated/Application.java",
                    "frontend/package.json",
                    "frontend/src/App.tsx",
                    "AUTOSPEC_MANIFEST.json"
            );
        }
    }

    @Test
    void failedSkeletonGenerationPersistsFailedJob() throws Exception {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("Failed Code Export Project");
        project.setOriginalRequirement("Export code skeleton with failure.");
        project.setStatus("COMPLETED");
        projectService.save(project);

        saveArtifact(project.getId(), "PRD", "{\"project_name\":\"Failed Code Export\"}");

        doThrow(new JsonProcessingException("manifest serialization failed") {
        }).when(objectMapper).writeValueAsString(any());

        assertThatThrownBy(() -> codeSkeletonService.generate(project.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Manifest generation failed");

        List<CodeGenerationJob> jobs = codeGenerationJobService.lambdaQuery()
                .eq(CodeGenerationJob::getProjectId, project.getId())
                .orderByAsc(CodeGenerationJob::getId)
                .list();
        assertThat(jobs).hasSize(1);
        CodeGenerationJob failedJob = jobs.get(0);
        assertThat(failedJob.getStatus()).isEqualTo("FAILED");
        assertThat(failedJob.getErrorMessage()).contains("Manifest generation failed");
        assertThat(failedJob.getCompletedAt()).isNotNull();
        assertThat(exportFileService.lambdaQuery()
                .eq(ExportFile::getProjectId, project.getId())
                .count()).isZero();
    }

    @Test
    void staleRunningCodeGenerationJobsCanBeTimedOut() {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("Timeout Recovery Project");
        project.setOriginalRequirement("Recover stale code generation jobs.");
        project.setStatus("COMPLETED");
        projectService.save(project);

        LocalDateTime now = LocalDateTime.now();
        CodeGenerationJob staleRunning = codeGenerationJob(project.getId(), "RUNNING", now.minusHours(2));
        CodeGenerationJob recentRunning = codeGenerationJob(project.getId(), "RUNNING", now.minusMinutes(5));
        CodeGenerationJob alreadyFailed = codeGenerationJob(project.getId(), "FAILED", now.minusHours(3));

        int timedOut = codeGenerationJobService.timeoutRunningJobsBefore(now.minusMinutes(30));

        assertThat(timedOut).isEqualTo(1);
        CodeGenerationJob timedOutJob = codeGenerationJobService.getById(staleRunning.getId());
        assertThat(timedOutJob.getStatus()).isEqualTo("FAILED");
        assertThat(timedOutJob.getErrorMessage()).isEqualTo("Timed out while running code generation job");
        assertThat(timedOutJob.getCompletedAt()).isNotNull();
        assertThat(codeGenerationJobService.getById(recentRunning.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(codeGenerationJobService.getById(alreadyFailed.getId()).getStatus()).isEqualTo("FAILED");
    }

    private void saveArtifact(Long projectId, String type, String content) {
        Artifact artifact = new Artifact();
        artifact.setProjectId(projectId);
        artifact.setType(type);
        artifact.setTitle(type);
        artifact.setContent(content);
        artifact.setFormat("JSON");
        artifact.setVersion(1);
        artifact.setStatus("GENERATED");
        artifactService.save(artifact);
    }

    private CodeGenerationJob codeGenerationJob(Long projectId, String status, LocalDateTime createdAt) {
        CodeGenerationJob job = new CodeGenerationJob();
        job.setProjectId(projectId);
        job.setStatus(status);
        job.setCreatedAt(createdAt);
        codeGenerationJobService.save(job);
        return job;
    }
}
