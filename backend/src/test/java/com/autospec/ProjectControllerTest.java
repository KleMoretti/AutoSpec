package com.autospec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.autospec.service.AgentEngineClient;
import com.autospec.service.AgentEngineExecutionRecord;
import com.autospec.service.AgentGenerationResult;
import com.autospec.service.ArtifactService;
import com.autospec.service.KnowledgeIndexService;
import com.autospec.service.ProjectMemberService;
import com.autospec.service.ProjectService;
import com.autospec.service.UserAccountService;
import com.autospec.service.WorkflowSnapshotService;
import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.entity.ProjectMember;
import com.autospec.entity.UserAccount;
import com.autospec.entity.WorkflowSnapshot;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectControllerTest {

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
    private ProjectService projectService;

    @Autowired
    private ProjectMemberService projectMemberService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private KnowledgeIndexService knowledgeIndexService;

    @Autowired
    private WorkflowSnapshotService workflowSnapshotService;

    @Test
    void loginReturnsDemoSessionUser() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"owner","password":"owner-pass"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.username").value("owner"))
                .andExpect(jsonPath("$.displayName").value("Owner"))
                .andExpect(jsonPath("$.sessionToken").isString());
    }

    @Test
    void projectArtifactsRequireMembership() throws Exception {
        String token = loginToken();
        long projectId = createProject(token, "Private Project", "Build a private project.");

        mockMvc.perform(get("/api/projects/{projectId}/artifacts", projectId)
                        .header("X-AutoSpec-User-Id", "99999"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportRequiresProjectMembership() throws Exception {
        String token = loginToken();
        long projectId = createProject(token, "Export Project", "Build exportable artifacts.");

        artifact(projectId, "PRD", "Export PRD", """
                {
                  "project_name": "Export",
                  "target_users": [],
                  "core_features": [],
                  "user_stories": [],
                  "business_boundaries": [],
                  "non_functional_requirements": [],
                  "risks": []
                }
                """);
        artifact(projectId, "BACKEND_DESIGN", "Export Backend", """
                {"tables":[],"apis":[]}
                """);
        artifact(projectId, "REVIEW_REPORT", "Export Review", """
                {"score":100,"issues":[]}
                """);

        mockMvc.perform(post("/api/projects/{projectId}/export?format=MARKDOWN", projectId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/projects/{projectId}/export?format=MARKDOWN", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("MARKDOWN"));
    }

    @Test
    void approvingArtifactIndexesKnowledgeSources() throws Exception {
        String token = loginToken();
        long projectId = createProject(token, "Knowledge Source", "Build a campus marketplace.");

        Artifact artifact = new Artifact();
        artifact.setProjectId(projectId);
        artifact.setType("PRD");
        artifact.setTitle("Knowledge Source PRD");
        artifact.setContent("{\"project_name\":\"Campus\",\"core_features\":[{\"name\":\"favorites\"}]}");
        artifact.setFormat("JSON");
        artifact.setVersion(1);
        artifact.setStatus("PENDING_REVIEW");
        artifactService.save(artifact);

        mockMvc.perform(post("/api/projects/{projectId}/artifacts/{artifactId}/approve", projectId, artifact.getId())
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/{projectId}/knowledge/sources", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].artifactType").value("PRD"))
                .andExpect(jsonPath("$[0].title").value("Knowledge Source PRD"));
    }

    @Test
    void generationRecordsModelInvocations() throws Exception {
        String token = loginToken();
        when(agentEngineClient.generatePrd(contains("model routing"), anyList()))
                .thenReturn(new AgentGenerationResult(
                        """
                                {
                                  "project_name": "Routing",
                                  "target_users": ["admin"],
                                  "core_features": [],
                                  "user_stories": [],
                                  "business_boundaries": [],
                                  "non_functional_requirements": [],
                                  "risks": []
                                }
                                """,
                        null,
                        null,
                        List.of(new AgentEngineExecutionRecord(
                                "product_manager",
                                "ProductManagerAgent_v1",
                                "SUCCEEDED",
                                "{\"requirement\":\"model routing\"}",
                                "{\"project_name\":\"Routing\"}",
                                null,
                                42,
                                "ProductManagerAgent",
                                "local",
                                "deterministic-fixture"
                        ))
                ));
        long projectId = createProject(token, "Routed Project", "Build model routing.");

        mockMvc.perform(post("/api/projects/{projectId}/generate-prd", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/{projectId}/model-invocations", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentNode").value("product_manager"))
                .andExpect(jsonPath("$[0].providerKey").value("local"))
                .andExpect(jsonPath("$[0].modelName").value("deterministic-fixture"))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
    }

    @Test
    void generationRetrievesOnlySourcesVisibleToProjectOwner() throws Exception {
        String token = loginToken();
        long ownerSourceProjectId = createProject(token, "Owner Source", "Build model routing marketplace.");
        Artifact ownerArtifact = approvedArtifact(ownerSourceProjectId, "Owner Source PRD", "model routing marketplace owner source");
        knowledgeIndexService.indexApprovedArtifact(ownerArtifact);

        UserAccount otherUser = new UserAccount();
        otherUser.setUsername("other-user");
        otherUser.setDisplayName("Other User");
        otherUser.setPasswordHash("hash");
        otherUser.setEnabled(true);
        userAccountService.save(otherUser);

        Project otherProject = projectService.createProject("Other Source", "Build model routing marketplace.");
        otherProject.setUserId(otherUser.getId());
        projectService.updateById(otherProject);
        ProjectMember otherMember = new ProjectMember();
        otherMember.setProjectId(otherProject.getId());
        otherMember.setUserId(otherUser.getId());
        otherMember.setRole("OWNER");
        projectMemberService.save(otherMember);
        Artifact otherArtifact = approvedArtifact(otherProject.getId(), "Other Tenant PRD", "model routing marketplace other source");
        knowledgeIndexService.indexApprovedArtifact(otherArtifact);

        when(agentEngineClient.generatePrd(contains("model routing"), anyList()))
                .thenReturn(new AgentGenerationResult("""
                        {
                          "project_name": "Routing",
                          "target_users": ["admin"],
                          "core_features": [],
                          "user_stories": [],
                          "business_boundaries": [],
                          "non_functional_requirements": [],
                          "risks": []
                        }
                        """, null, null, List.of()));

        long projectId = createProject(token, "Routed Project", "Build model routing marketplace.");
        mockMvc.perform(post("/api/projects/{projectId}/generate-prd", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk());

        ArgumentCaptor<List> sourcesCaptor = ArgumentCaptor.forClass(List.class);
        verify(agentEngineClient).generatePrd(eq("Build model routing marketplace."), sourcesCaptor.capture());
        assertThat(sourcesCaptor.getValue().toString())
                .contains("Owner Source PRD")
                .doesNotContain("Other Tenant PRD");
    }

    @Test
    void workflowEndpointReturnsNodesEdgesAndEvents() throws Exception {
        String token = loginToken();
        long projectId = createProject(token, "Workflow Project", "Visualize workflow.");

        WorkflowSnapshot snapshot = new WorkflowSnapshot();
        snapshot.setProjectId(projectId);
        snapshot.setWorkflowKey("autospec-v3");
        snapshot.setVersion("v3");
        snapshot.setGraphJson("""
                {"nodes":[{"id":"product_manager","label":"Product Manager"}],"edges":[]}
                """);
        workflowSnapshotService.save(snapshot);

        mockMvc.perform(get("/api/projects/{projectId}/workflow", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowKey").value("autospec-v3"))
                .andExpect(jsonPath("$.nodes[0].id").value("product_manager"));
    }

    @Test
    void projectGenerationFlowPersistsProgressArtifactsAndReview() throws Exception {
        String token = loginToken();
        when(agentEngineClient.generate(contains("campus second-hand marketplace")))
                .thenReturn(agentResultFromAgentService());

        String createBody = """
                {
                  "name": "Campus Marketplace",
                  "requirement": "Build a campus second-hand marketplace with product publishing, favorites, search, and admin audit."
                }
                """;

        String createResponse = mockMvc.perform(post("/api/projects")
                        .header(SESSION_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.projectId").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createJson = objectMapper.readTree(createResponse);
        long projectId = createJson.get("projectId").asLong();

        mockMvc.perform(post("/api/projects/{projectId}/generate", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.percent").value(100));

        verify(agentEngineClient).generate(contains("campus second-hand marketplace"));

        mockMvc.perform(get("/api/projects/{projectId}/progress", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.currentAgent").value("COMPLETED"))
                .andExpect(jsonPath("$.percent").value(100))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].agentName").value("ProductManagerAgent_v1"))
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"));

        String artifactResponse = mockMvc.perform(get("/api/projects/{projectId}/artifacts", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PRD"))
                .andExpect(jsonPath("$[1].type").value("BACKEND_DESIGN"))
                .andExpect(jsonPath("$[2].type").value("REVIEW_REPORT"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(artifactResponse)
                .contains("Agent Service Marketplace PRD")
                .contains("Agent Service Backend Design")
                .contains("Agent Service Review Report");

        mockMvc.perform(get("/api/projects/{projectId}/review", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.issues[0].issueType").value("SEMANTIC_REVIEW"));

        mockMvc.perform(post("/api/projects/{projectId}/export?format=MARKDOWN", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("MARKDOWN"))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("# Agent Service Marketplace")));
    }

    @Test
    void generationPersistsEvaluationReportArtifactWhenAgentEngineReturnsV4Report() throws Exception {
        String token = loginToken();
        when(agentEngineClient.generate(contains("agent evaluation")))
                .thenReturn(agentResultWithEvaluationReport());

        long projectId = createProject(token, "Evaluation Project", "Build agent evaluation.");

        mockMvc.perform(post("/api/projects/{projectId}/generate", projectId)
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        List<Artifact> artifacts = artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .orderByAsc(Artifact::getId)
                .list();

        assertThat(artifacts)
                .extracting(Artifact::getType)
                .contains("EVALUATION_REPORT");
        Artifact evaluationReport = artifacts.stream()
                .filter(artifact -> "EVALUATION_REPORT".equals(artifact.getType()))
                .findFirst()
                .orElseThrow();
        assertThat(evaluationReport.getTitle()).isEqualTo("Evaluation Project Evaluation Report");
        assertThat(evaluationReport.getSourceAgent()).isEqualTo("EvaluatorAgent_v1");
        assertThat(evaluationReport.getContent())
                .contains("\"overall_score\"")
                .contains("92");
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

    private Artifact approvedArtifact(Long projectId, String title, String content) {
        return artifact(projectId, "PRD", title, content, "APPROVED");
    }

    private Artifact artifact(Long projectId, String type, String title, String content) {
        return artifact(projectId, type, title, content, "GENERATED");
    }

    private Artifact artifact(Long projectId, String type, String title, String content, String status) {
        Artifact artifact = new Artifact();
        artifact.setProjectId(projectId);
        artifact.setType(type);
        artifact.setTitle(title);
        artifact.setContent(content);
        artifact.setFormat("JSON");
        artifact.setVersion(1);
        artifact.setStatus(status);
        artifactService.save(artifact);
        return artifact;
    }

    private AgentGenerationResult agentResultFromAgentService() {
        String prdJson = """
                {
                  "project_name": "Agent Service Marketplace",
                  "target_users": ["student", "admin"],
                  "core_features": [
                    {"name": "Agent Service Marketplace PRD", "description": "Generated by Python agent service.", "priority": "MUST"}
                  ],
                  "user_stories": [
                    {"role": "student", "goal": "publish a product", "benefit": "find a buyer", "acceptance_criteria": ["Saved through backend."]}
                  ],
                  "business_boundaries": ["No escrow."],
                  "non_functional_requirements": ["Record agent execution."],
                  "risks": ["Generated content needs review."]
                }
                """;
        String backendJson = """
                {
                  "tables": [
                    {
                      "name": "agent_product",
                      "description": "Agent Service Backend Design",
                      "fields": [
                        {"name": "id", "type": "BIGINT", "nullable": false, "description": "Primary key."}
                      ]
                    }
                  ],
                  "apis": [
                    {"method": "POST", "path": "/api/agent-products", "description": "Generated by agent service.", "request_params": [], "response_fields": [], "auth_required": true, "required_roles": ["STUDENT"]}
                  ]
                }
                """;
        String reviewJson = """
                {
                  "score": 88,
                  "issues": [
                    {
                      "severity": "MEDIUM",
                      "issue_type": "SEMANTIC_REVIEW",
                      "description": "Agent Service Review Report",
                      "suggestion": "Keep generated artifacts consistent."
                    }
                  ]
                }
                """;

        return new AgentGenerationResult(
                prdJson,
                backendJson,
                reviewJson,
                List.of(
                        new AgentEngineExecutionRecord("ProductManagerAgent_v1", "SUCCEEDED", "{\"requirement\":\"x\"}", prdJson, null),
                        new AgentEngineExecutionRecord("BackendEngineerAgent_v1", "SUCCEEDED", "{\"prd\":{}}", backendJson, null),
                        new AgentEngineExecutionRecord("ReviewerAgent_v1", "SUCCEEDED", "{\"backend_design\":{}}", reviewJson, null)
                )
        );
    }

    private AgentGenerationResult agentResultWithEvaluationReport() {
        String evaluationJson = """
                {
                  "overall_score": 92,
                  "final_grade": "A",
                  "dimension_scores": [
                    {"dimension": "RUNTIME_RELIABILITY", "score": 100, "rationale": "All nodes succeeded."}
                  ],
                  "issues": []
                }
                """;
        AgentGenerationResult base = agentResultFromAgentService();
        return new AgentGenerationResult(
                base.prdJson(),
                base.architectureDesignJson(),
                base.backendDesignJson(),
                base.frontendSkeletonJson(),
                base.reviewReportJson(),
                evaluationJson,
                List.of(
                        new AgentEngineExecutionRecord("evaluator", "EvaluatorAgent_v1", "SUCCEEDED", "{}", evaluationJson, null, 12, "EvaluatorAgent")
                )
        );
    }
}
