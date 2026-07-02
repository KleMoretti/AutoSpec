package com.autospec;

import com.autospec.entity.AgentTask;
import com.autospec.entity.AgentEvent;
import com.autospec.entity.Artifact;
import com.autospec.entity.ProjectMember;
import com.autospec.entity.PromptVersion;
import com.autospec.entity.Project;
import com.autospec.entity.ReviewIssue;
import com.autospec.entity.UserAccount;
import com.autospec.entity.WorkflowSnapshot;
import com.autospec.service.AgentEventService;
import com.autospec.service.AgentTaskService;
import com.autospec.service.ArtifactService;
import com.autospec.service.ProjectMemberService;
import com.autospec.service.PromptVersionService;
import com.autospec.service.ProjectService;
import com.autospec.service.ReviewIssueService;
import com.autospec.service.UserAccountService;
import com.autospec.service.WorkflowSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CoreDataModelTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private AgentEventService agentEventService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private PromptVersionService promptVersionService;

    @Autowired
    private ReviewIssueService reviewIssueService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private ProjectMemberService projectMemberService;

    @Autowired
    private WorkflowSnapshotService workflowSnapshotService;

    @Test
    void persistsProjectArtifactsAgentTasksAndReviewIssues() {
        Project project = new Project();
        project.setUserId(1001L);
        project.setName("Campus Marketplace");
        project.setOriginalRequirement("Build a campus second-hand marketplace.");
        project.setStatus("CREATED");

        assertThat(projectService.save(project)).isTrue();
        assertThat(project.getId()).isNotNull();

        Project loadedProject = projectService.getById(project.getId());
        assertThat(loadedProject.getName()).isEqualTo("Campus Marketplace");

        loadedProject.setStatus("GENERATING");
        assertThat(projectService.updateById(loadedProject)).isTrue();
        assertThat(projectService.getById(project.getId()).getStatus()).isEqualTo("GENERATING");

        AgentTask task = new AgentTask();
        task.setProjectId(project.getId());
        task.setAgentName("ProductManagerAgent_v1");
        task.setNodeName("product_manager");
        task.setStatus("SUCCEEDED");
        task.setInputText("{\"requirement\":\"marketplace\"}");
        task.setOutputText("{\"project_name\":\"Campus Marketplace\"}");
        task.setDurationMs(120);
        task.setStartTime(LocalDateTime.now().minusSeconds(2));
        task.setEndTime(LocalDateTime.now());

        assertThat(agentTaskService.save(task)).isTrue();
        assertThat(agentTaskService.getById(task.getId()).getAgentName()).isEqualTo("ProductManagerAgent_v1");

        Artifact artifact = new Artifact();
        artifact.setProjectId(project.getId());
        artifact.setType("PRD");
        artifact.setTitle("Campus Marketplace PRD");
        artifact.setContent("{\"project_name\":\"Campus Marketplace\"}");
        artifact.setFormat("JSON");
        artifact.setVersion(1);
        artifact.setStatus("PENDING_REVIEW");
        artifact.setSourceAgent("ProductManagerAgent_v1");

        assertThat(artifactService.save(artifact)).isTrue();
        assertThat(artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, project.getId())
                .eq(Artifact::getType, "PRD")
                .one()
                .getVersion()).isEqualTo(1);
        assertThat(artifactService.getById(artifact.getId()).getStatus()).isEqualTo("PENDING_REVIEW");

        PromptVersion prompt = new PromptVersion();
        prompt.setPromptKey("ProductManagerAgent");
        prompt.setVersion("v2");
        prompt.setContent("Generate structured PRD JSON.");
        prompt.setChecksum("sha256:test");
        prompt.setActive(true);

        assertThat(promptVersionService.save(prompt)).isTrue();
        assertThat(promptVersionService.getById(prompt.getId()).getPromptKey()).isEqualTo("ProductManagerAgent");

        AgentEvent event = new AgentEvent();
        event.setProjectId(project.getId());
        event.setTaskId(task.getId());
        event.setEventType("NODE_SUCCEEDED");
        event.setNodeName("product_manager");
        event.setMessage("Product Manager completed.");
        event.setPayload("{\"agent\":\"ProductManagerAgent_v2\"}");

        assertThat(agentEventService.save(event)).isTrue();
        assertThat(agentEventService.lambdaQuery()
                .eq(AgentEvent::getProjectId, project.getId())
                .eq(AgentEvent::getEventType, "NODE_SUCCEEDED")
                .count()).isEqualTo(1);

        ReviewIssue issue = new ReviewIssue();
        issue.setProjectId(project.getId());
        issue.setSeverity("HIGH");
        issue.setIssueType("API_COVERAGE");
        issue.setDescription("Favorite feature is missing an API.");
        issue.setSuggestion("Add POST /api/favorites.");
        issue.setStatus("OPEN");

        assertThat(reviewIssueService.save(issue)).isTrue();
        assertThat(reviewIssueService.lambdaQuery()
                .eq(ReviewIssue::getProjectId, project.getId())
                .eq(ReviewIssue::getStatus, "OPEN")
                .count()).isEqualTo(1);

        UserAccount user = new UserAccount();
        user.setUsername("alice");
        user.setDisplayName("Alice");
        user.setPasswordHash("sha256:test");
        user.setEnabled(true);
        assertThat(userAccountService.save(user)).isTrue();

        ProjectMember member = new ProjectMember();
        member.setProjectId(project.getId());
        member.setUserId(user.getId());
        member.setRole("OWNER");
        assertThat(projectMemberService.save(member)).isTrue();

        WorkflowSnapshot snapshot = new WorkflowSnapshot();
        snapshot.setProjectId(project.getId());
        snapshot.setWorkflowKey("autospec-v3");
        snapshot.setVersion("v3");
        snapshot.setGraphJson("{\"nodes\":[\"product_manager\"],\"edges\":[]}");
        assertThat(workflowSnapshotService.save(snapshot)).isTrue();
    }
}
