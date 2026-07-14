package com.autospec.workflow;

import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowDefinition;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowApprovalMapper;
import com.autospec.mapper.WorkflowDefinitionMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.ProjectService;
import com.autospec.service.WorkflowApprovalService;
import com.autospec.service.WorkflowRunCreationService;
import com.autospec.service.WorkflowVersionManagementService;
import com.autospec.workflow.runtime.ReviewerReworkCoordinator;
import com.autospec.workflow.runtime.WorkflowApprovalCoordinator;
import com.autospec.workflow.runtime.WorkflowArtifactProjector;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.autospec.workflow.runtime.WorkflowRunReconciliationService;
import com.autospec.workflow.transport.WorkflowEventConsumer;
import com.autospec.workflow.transport.WorkflowEventOutcome;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DynamicWorkflowLifecycleTest {
    @Autowired
    private ProjectService projectService;
    @Autowired
    private WorkflowRunCreationService runCreationService;
    @Autowired
    private WorkflowVersionManagementService versionManagementService;
    @Autowired
    private WorkflowApprovalService approvalService;
    @Autowired
    private WorkflowRunMapper runMapper;
    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;
    @Autowired
    private WorkflowApprovalMapper approvalMapper;
    @Autowired
    private WorkflowDefinitionMapper definitionMapper;
    @Autowired
    private WorkflowVersionMapper versionMapper;
    @Autowired
    private ArtifactMapper artifactMapper;
    @Autowired
    private ProcessedWorkflowEventMapper processedEventMapper;
    @Autowired
    private WorkflowRunReconciliationService reconciliationService;
    @Autowired
    private WorkflowFailureDecisionService failureDecisionService;
    @Autowired
    private WorkflowApprovalCoordinator approvalCoordinator;
    @Autowired
    private WorkflowArtifactProjector artifactProjector;
    @Autowired
    private ReviewerReworkCoordinator reworkCoordinator;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publishedV5RunProjectsArtifactsAssemblesInputsAndCompletesIdempotently() throws Exception {
        Project project = project();
        WorkflowVersion version = builtInV5Version();
        WorkflowRun run = runCreationService.start(new WorkflowRunCreationService.StartCommand(
                project.getId(),
                version.getId(),
                "{\"requirement\":\"Build a campus marketplace\",\"retrieved_sources\":[]}",
                "v5-run-" + UUID.randomUUID()
        ));
        WorkflowEventConsumer consumer = consumer();

        WorkflowNodeRun productManager = node(run.getId(), "product_manager");
        assertThat(productManager.getStatus()).isEqualTo("QUEUED");
        assertThat(consumer.consume(success(productManager, "{\"title\":\"Marketplace PRD\"}")))
                .isEqualTo(WorkflowEventOutcome.ACCEPTED);
        WorkflowApproval approval = approvalMapper.selectOne(new LambdaQueryWrapper<WorkflowApproval>()
                .eq(WorkflowApproval::getNodeRunId, productManager.getId()));
        assertThat(approval.getStatus()).isEqualTo("PENDING");
        Artifact candidate = artifactMapper.selectById(approval.getCandidateArtifactId());
        assertThat(candidate.getStatus()).isEqualTo("PENDING_REVIEW");

        approvalService.decide(approval.getId(), new WorkflowApprovalService.ApprovalDecision(
                "APPROVE",
                "accept deterministic PRD",
                null,
                null,
                "approve-" + UUID.randomUUID(),
                project.getUserId()
        ));
        assertThat(artifactMapper.selectById(candidate.getId()).getStatus()).isEqualTo("APPROVED");

        WorkflowNodeRun architect = node(run.getId(), "architect");
        assertThat(architect.getStatus()).isEqualTo("QUEUED");
        assertThat(objectMapper.readTree(architect.getInputJson()).path("prd").path("title").asText())
                .isEqualTo("Marketplace PRD");
        consumer.consume(success(architect, "{\"style\":\"modular\"}"));

        WorkflowNodeRun backend = node(run.getId(), "backend_engineer");
        WorkflowNodeRun frontend = node(run.getId(), "frontend_engineer");
        assertThat(List.of(backend, frontend))
                .extracting(WorkflowNodeRun::getStatus)
                .containsOnly("QUEUED");
        JsonNode backendInput = objectMapper.readTree(backend.getInputJson());
        assertThat(backendInput.path("prd").path("title").asText()).isEqualTo("Marketplace PRD");
        assertThat(backendInput.path("architecture_design").path("style").asText()).isEqualTo("modular");
        consumer.consume(success(backend, "{\"api_endpoints\":[]}"));
        consumer.consume(success(frontend, "{\"pages\":[]}"));

        WorkflowNodeRun reviewer = node(run.getId(), "reviewer");
        assertThat(reviewer.getStatus()).isEqualTo("QUEUED");
        JsonNode reviewerInput = objectMapper.readTree(reviewer.getInputJson());
        assertThat(reviewerInput.has("backend_design")).isTrue();
        assertThat(reviewerInput.has("frontend_skeleton")).isTrue();
        consumer.consume(success(reviewer, "{\"decision\":\"PASS\",\"routes\":[]}"));

        WorkflowNodeRun evaluator = node(run.getId(), "evaluator");
        assertThat(evaluator.getStatus()).isEqualTo("QUEUED");
        String evaluatorEvent = success(evaluator, "{\"overall_score\":92}");
        assertThat(consumer.consume(evaluatorEvent)).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        assertThat(consumer.consume(evaluatorEvent)).isEqualTo(WorkflowEventOutcome.DUPLICATE);

        WorkflowRun completed = runMapper.selectById(run.getId());
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getResponsePercent()).isEqualTo(100);
        assertThat(artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getProjectId, project.getId())))
                .extracting(Artifact::getType, Artifact::getWorkflowNodeRunId)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("PRD", productManager.getId()),
                        Tuple.tuple("ARCHITECTURE_DESIGN", architect.getId()),
                        Tuple.tuple("BACKEND_DESIGN", backend.getId()),
                        Tuple.tuple("FRONTEND_SKELETON", frontend.getId()),
                        Tuple.tuple("REVIEW_REPORT", reviewer.getId()),
                        Tuple.tuple("EVALUATION_REPORT", evaluator.getId())
                );
    }

    @Test
    void workflowDraftMustValidateBeforeItCanBePublished() {
        String workflowKey = "fixture-" + UUID.randomUUID();
        String versionName = "v" + System.nanoTime();
        String spec = """
                {
                  "workflow_key":"%s",
                  "version":"%s",
                  "nodes":[{
                    "node_id":"only",
                    "agent_name":"FixtureAgent_v1",
                    "artifact_type":"FIXTURE",
                    "depends_on":[]
                  }],
                  "edges":[]
                }
                """.formatted(workflowKey, versionName);

        WorkflowVersion draft = versionManagementService.createDraft(
                new WorkflowVersionManagementService.CreateDraftCommand(
                        workflowKey,
                        "Fixture workflow",
                        "version lifecycle test",
                        versionName,
                        spec
                )
        );

        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(versionManagementService.validate(draft.getId()).valid()).isTrue();
        assertThat(versionManagementService.publish(draft.getId()).getStatus()).isEqualTo("PUBLISHED");
    }

    private WorkflowEventConsumer consumer() {
        return new WorkflowEventConsumer(
                processedEventMapper,
                nodeRunMapper,
                reconciliationService,
                failureDecisionService,
                objectMapper,
                approvalCoordinator,
                artifactProjector,
                reworkCoordinator
        );
    }

    private Project project() {
        Project project = new Project();
        project.setUserId(1L);
        project.setName("v5-lifecycle-" + UUID.randomUUID());
        project.setOriginalRequirement("Build a campus marketplace");
        project.setStatus("GENERATING");
        projectService.save(project);
        return project;
    }

    private WorkflowVersion builtInV5Version() {
        WorkflowDefinition definition = definitionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowDefinition>()
                        .eq(WorkflowDefinition::getWorkflowKey, "autospec-v5")
        );
        return versionMapper.selectOne(new LambdaQueryWrapper<WorkflowVersion>()
                .eq(WorkflowVersion::getDefinitionId, definition.getId())
                .eq(WorkflowVersion::getVersion, "v5"));
    }

    private WorkflowNodeRun node(long runId, String nodeId) {
        return nodeRunMapper.selectOne(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                .eq(WorkflowNodeRun::getNodeId, nodeId)
                .orderByDesc(WorkflowNodeRun::getRevision)
                .orderByDesc(WorkflowNodeRun::getAttempt)
                .last("limit 1"));
    }

    private String success(WorkflowNodeRun node, String outputJson) {
        return """
                {
                  "event_id":"%s",
                  "source_event_id":"command-%d",
                  "event_type":"NODE_SUCCEEDED",
                  "workflow_run_id":%d,
                  "node_run_id":%d,
                  "node_id":"%s",
                  "revision":%d,
                  "attempt":%d,
                  "execution_id":"%s",
                  "duration_ms":12,
                  "output_payload":%s
                }
                """.formatted(
                UUID.randomUUID(),
                node.getId(),
                node.getWorkflowRunId(),
                node.getId(),
                node.getNodeId(),
                node.getRevision(),
                node.getAttempt(),
                node.getExecutionId(),
                outputJson
        );
    }
}
