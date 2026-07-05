package com.autospec;

import com.autospec.entity.AgentTask;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.entity.ModelInvocation;
import com.autospec.entity.Project;
import com.autospec.entity.UserAccount;
import com.autospec.entity.WorkflowRun;
import com.autospec.service.AgentEngineClient;
import com.autospec.service.AgentTaskService;
import com.autospec.service.AuditEventService;
import com.autospec.service.AuthService;
import com.autospec.service.CodeGenerationJobService;
import com.autospec.service.ExternalCallLogService;
import com.autospec.service.ModelInvocationService;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.ProjectService;
import com.autospec.service.WorkflowRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendDiagnosticsControllerTest {

    private static final String SESSION_HEADER = "X-AutoSpec-Session-Token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private WorkflowRunService workflowRunService;

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private AuditEventService auditEventService;

    @Autowired
    private ExternalCallLogService externalCallLogService;

    @Autowired
    private ModelInvocationService modelInvocationService;

    @Autowired
    private CodeGenerationJobService codeGenerationJobService;

    @MockBean
    private AgentEngineClient agentEngineClient;

    @Test
    void diagnosticsSummarizeWorkflowAuditExternalCallAndModelTelemetry() throws Exception {
        UserAccount owner = authService.ensureDemoOwner();
        String token = authService.issueSession(owner);
        Project project = projectService.createProject("diagnostics-" + System.nanoTime(), "Build diagnostic view.");
        project.setUserId(owner.getId());
        projectService.updateById(project);
        projectAccessService.addOwner(project.getId(), owner.getId());

        WorkflowRun completedRun = workflowRun(project.getId(), "wf-old", "COMPLETED");
        workflowRunService.save(completedRun);
        WorkflowRun failedRun = workflowRun(project.getId(), "wf-latest", "FAILED");
        workflowRunService.save(failedRun);
        agentTaskService.save(agentTask(project.getId(), "product_manager", "SUCCEEDED"));
        agentTaskService.save(agentTask(project.getId(), "api_designer", "FAILED"));

        auditEventService.record(
                project.getId(),
                owner.getId(),
                "wf-latest",
                "WORKFLOW_RUN_FAILED",
                "WORKFLOW_RUN",
                failedRun.getId(),
                "V4 workflow run failed",
                "{}"
        );
        externalCallLogService.record(
                project.getId(),
                "agent-engine",
                "wf-old",
                "GENERATE_V4",
                "SUCCEEDED",
                12,
                "{\"correlationId\":\"wf-old\"}",
                null,
                LocalDateTime.now().minusSeconds(3),
                LocalDateTime.now().minusSeconds(2)
        );
        externalCallLogService.record(
                project.getId(),
                "agent-engine",
                "wf-latest",
                "GENERATE_V4",
                "FAILED",
                34,
                "{\"correlationId\":\"wf-latest\"}",
                "Agent Engine unavailable",
                LocalDateTime.now().minusSeconds(1),
                LocalDateTime.now()
        );
        modelInvocationService.save(modelInvocation(project.getId(), failedRun.getId(), "wf-latest"));
        codeGenerationJobService.save(codeGenerationJob(project.getId(), "RUNNING", null));
        codeGenerationJobService.save(codeGenerationJob(project.getId(), "FAILED", "Code export timed out"));
        codeGenerationJobService.save(codeGenerationJob(project.getId(), "CANCELLED", "Cancelled by user"));

        mockMvc.perform(get("/api/projects/{projectId}/diagnostics", project.getId())
                        .header(SESSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.getId()))
                .andExpect(jsonPath("$.latestWorkflowRunId").value(failedRun.getId()))
                .andExpect(jsonPath("$.latestCorrelationId").value("wf-latest"))
                .andExpect(jsonPath("$.workflowRunCount").value(2))
                .andExpect(jsonPath("$.failedWorkflowRunCount").value(1))
                .andExpect(jsonPath("$.agentTaskCount").value(2))
                .andExpect(jsonPath("$.failedAgentTaskCount").value(1))
                .andExpect(jsonPath("$.latestFailedAgentTaskNodeName").value("api_designer"))
                .andExpect(jsonPath("$.latestFailedAgentTaskErrorMessage").value("API schema mismatch"))
                .andExpect(jsonPath("$.auditEventCount").value(1))
                .andExpect(jsonPath("$.externalCallCount").value(2))
                .andExpect(jsonPath("$.failedExternalCallCount").value(1))
                .andExpect(jsonPath("$.latestFailedExternalCallErrorMessage").value("Agent Engine unavailable"))
                .andExpect(jsonPath("$.latestFailedExternalCallDurationMs").value(34))
                .andExpect(jsonPath("$.modelInvocationCount").value(1))
                .andExpect(jsonPath("$.failedModelInvocationCount").value(1))
                .andExpect(jsonPath("$.latestFailedModelInvocationAgentNode").value("evaluator"))
                .andExpect(jsonPath("$.latestFailedModelInvocationModelName").value("deterministic-fixture"))
                .andExpect(jsonPath("$.latestFailedModelInvocationDurationMs").value(34))
                .andExpect(jsonPath("$.latestFailedModelInvocationErrorMessage").value("Agent Engine unavailable"))
                .andExpect(jsonPath("$.codeGenerationJobCount").value(3))
                .andExpect(jsonPath("$.runningCodeGenerationJobCount").value(1))
                .andExpect(jsonPath("$.failedCodeGenerationJobCount").value(1))
                .andExpect(jsonPath("$.cancelledCodeGenerationJobCount").value(1))
                .andExpect(jsonPath("$.latestFailedCodeGenerationJobErrorMessage").value("Code export timed out"));
    }

    private WorkflowRun workflowRun(Long projectId, String correlationId, String status) {
        WorkflowRun run = new WorkflowRun();
        run.setProjectId(projectId);
        run.setOperation("GENERATE_V4");
        run.setIdempotencyKey(correlationId + "-key");
        run.setCorrelationId(correlationId);
        run.setStatus(status);
        run.setStartedAt(LocalDateTime.now().minusSeconds(5));
        run.setCompletedAt(LocalDateTime.now());
        return run;
    }

    private AgentTask agentTask(Long projectId, String nodeName, String status) {
        AgentTask task = new AgentTask();
        task.setProjectId(projectId);
        task.setAgentName(nodeName);
        task.setNodeName(nodeName);
        task.setStatus(status);
        task.setInputText("{}");
        task.setOutputText("{}");
        if ("FAILED".equals(status)) {
            task.setErrorMessage("API schema mismatch");
        }
        task.setDurationMs(10);
        task.setStartTime(LocalDateTime.now().minusSeconds(3));
        task.setEndTime(LocalDateTime.now().minusSeconds(2));
        return task;
    }

    private ModelInvocation modelInvocation(Long projectId, Long workflowRunId, String correlationId) {
        ModelInvocation invocation = new ModelInvocation();
        invocation.setProjectId(projectId);
        invocation.setWorkflowRunId(workflowRunId);
        invocation.setCorrelationId(correlationId);
        invocation.setProviderKey("local");
        invocation.setModelName("deterministic-fixture");
        invocation.setAgentNode("evaluator");
        invocation.setStatus("FAILED");
        invocation.setDurationMs(34);
        invocation.setInputTokens(0);
        invocation.setOutputTokens(0);
        invocation.setScore(BigDecimal.ZERO);
        invocation.setErrorMessage("Agent Engine unavailable");
        return invocation;
    }

    private CodeGenerationJob codeGenerationJob(Long projectId, String status, String errorMessage) {
        CodeGenerationJob job = new CodeGenerationJob();
        job.setProjectId(projectId);
        job.setStatus(status);
        job.setManifest("{}");
        job.setErrorMessage(errorMessage);
        job.setCreatedAt(LocalDateTime.now().minusSeconds(1));
        if (!"RUNNING".equals(status)) {
            job.setCompletedAt(LocalDateTime.now());
        }
        if ("CANCELLED".equals(status)) {
            job.setCancelledAt(LocalDateTime.now());
        }
        return job;
    }
}
