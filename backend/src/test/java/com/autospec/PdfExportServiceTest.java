package com.autospec;

import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.service.ArtifactService;
import com.autospec.service.PdfExportService;
import com.autospec.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PdfExportServiceTest {

    @Autowired
    private PdfExportService pdfExportService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ArtifactService artifactService;

    @Test
    void rendersProjectArtifactsToNonEmptyPdf() {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("PDF Export Project");
        project.setOriginalRequirement("Export AutoSpec artifacts.");
        project.setStatus("COMPLETED");
        projectService.save(project);

        saveArtifact(project.getId(), "PRD", prdJson());
        saveArtifact(project.getId(), "BACKEND_DESIGN", backendJson());
        saveArtifact(project.getId(), "REVIEW_REPORT", reviewJson());

        byte[] pdf = pdfExportService.exportProject(project.getId());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
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

    private String prdJson() {
        return """
                {
                  "project_name": "PDF Export Project",
                  "target_users": ["student"],
                  "core_features": [{"name": "Export", "description": "Export project.", "priority": "MUST"}],
                  "user_stories": [{"role": "student", "goal": "export", "benefit": "share", "acceptance_criteria": ["PDF exists."]}],
                  "business_boundaries": [],
                  "non_functional_requirements": [],
                  "risks": []
                }
                """;
    }

    private String backendJson() {
        return """
                {
                  "tables": [{"name": "project", "description": "Project data.", "fields": [{"name": "id", "type": "BIGINT", "nullable": false, "description": "Primary key."}]}],
                  "apis": [{"method": "POST", "path": "/api/projects", "description": "Create project.", "request_params": [], "response_fields": [], "auth_required": false, "required_roles": []}]
                }
                """;
    }

    private String reviewJson() {
        return """
                {"score": 100, "issues": []}
                """;
    }
}
