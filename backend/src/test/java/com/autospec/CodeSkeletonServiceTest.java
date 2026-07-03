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
}
