package com.autospec;

import com.autospec.dto.CodeGenerationResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.service.ArtifactService;
import com.autospec.service.CodeSkeletonService;
import com.autospec.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CodeSkeletonServiceTest {

    @Autowired
    private CodeSkeletonService codeSkeletonService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ArtifactService artifactService;

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
