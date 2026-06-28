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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentEngineClient agentEngineClient;

    @Test
    void projectGenerationFlowPersistsProgressArtifactsAndReview() throws Exception {
        when(agentEngineClient.generate(contains("campus second-hand marketplace")))
                .thenReturn(agentResultFromAgentService());

        String createBody = """
                {
                  "name": "Campus Marketplace",
                  "requirement": "Build a campus second-hand marketplace with product publishing, favorites, search, and admin audit."
                }
                """;

        String createResponse = mockMvc.perform(post("/api/projects")
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

        mockMvc.perform(post("/api/projects/{projectId}/generate", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.percent").value(100));

        verify(agentEngineClient).generate(contains("campus second-hand marketplace"));

        mockMvc.perform(get("/api/projects/{projectId}/progress", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.currentAgent").value("COMPLETED"))
                .andExpect(jsonPath("$.percent").value(100))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].agentName").value("ProductManagerAgent_v1"))
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"));

        String artifactResponse = mockMvc.perform(get("/api/projects/{projectId}/artifacts", projectId))
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

        mockMvc.perform(get("/api/projects/{projectId}/review", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.issues[0].issueType").value("SEMANTIC_REVIEW"));

        mockMvc.perform(post("/api/projects/{projectId}/export?format=MARKDOWN", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("MARKDOWN"))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("# Agent Service Marketplace")));
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
}
