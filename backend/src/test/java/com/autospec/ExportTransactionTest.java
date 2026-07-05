package com.autospec;

import com.autospec.entity.Artifact;
import com.autospec.entity.ExportFile;
import com.autospec.service.AgentEngineClient;
import com.autospec.service.ArtifactService;
import com.autospec.service.AuditEventService;
import com.autospec.service.ExportFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExportTransactionTest {

    private static final String SESSION_HEADER = "X-AutoSpec-Session-Token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentEngineClient agentEngineClient;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ExportFileService exportFileService;

    @SpyBean
    private AuditEventService auditEventService;

    @Test
    void exportFileIsRolledBackWhenAuditEventFails() throws Exception {
        String token = loginToken();
        long projectId = createProject(token, "Transactional Export Project", "Export atomically.");
        artifact(projectId, "PRD", "Transactional PRD", """
                {
                  "project_name": "Transactional Export",
                  "target_users": [],
                  "core_features": [],
                  "user_stories": [],
                  "business_boundaries": [],
                  "non_functional_requirements": [],
                  "risks": []
                }
                """);
        artifact(projectId, "BACKEND_DESIGN", "Transactional Backend", "{\"tables\":[],\"apis\":[]}");
        artifact(projectId, "REVIEW_REPORT", "Transactional Review", "{\"score\":100,\"issues\":[]}");

        doThrow(new IllegalStateException("audit write failed"))
                .when(auditEventService)
                .record(
                        eq(projectId),
                        org.mockito.ArgumentMatchers.<Long>any(),
                        eq("PROJECT_EXPORTED"),
                        eq("EXPORT_FILE"),
                        org.mockito.ArgumentMatchers.<Long>any(),
                        eq("Project exported as MARKDOWN"),
                        anyString()
                );

        assertThatThrownBy(() -> mockMvc.perform(post("/api/projects/{projectId}/export?format=MARKDOWN", projectId)
                        .header(SESSION_HEADER, token)))
                .hasRootCauseMessage("audit write failed");

        assertThat(exportFileService.lambdaQuery()
                .eq(ExportFile::getProjectId, projectId)
                .count()).isZero();
    }

    private String loginToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"owner","password":"owner-pass"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("sessionToken").asText();
    }

    private long createProject(String token, String name, String requirement) throws Exception {
        String createResponse = mockMvc.perform(post("/api/projects")
                        .header(SESSION_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", name,
                                "requirement", requirement
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(createResponse).get("projectId").asLong();
    }

    private Artifact artifact(Long projectId, String type, String title, String content) {
        Artifact artifact = new Artifact();
        artifact.setProjectId(projectId);
        artifact.setType(type);
        artifact.setTitle(title);
        artifact.setContent(content);
        artifact.setFormat("JSON");
        artifact.setVersion(1);
        artifact.setStatus("GENERATED");
        artifactService.save(artifact);
        return artifact;
    }
}
