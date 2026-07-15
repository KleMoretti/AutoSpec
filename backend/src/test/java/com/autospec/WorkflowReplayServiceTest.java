package com.autospec;

import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.entity.WorkflowDefinition;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowTransition;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.WorkflowDefinitionMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.mapper.WorkflowTransitionMapper;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.ProjectService;
import com.autospec.service.WorkflowReplayService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowReplayServiceTest {
    @Autowired
    private WorkflowReplayService replayService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WorkflowRunMapper runMapper;

    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Autowired
    private WorkflowTransitionMapper transitionMapper;

    @Autowired
    private WorkflowDefinitionMapper definitionMapper;

    @Autowired
    private WorkflowVersionMapper versionMapper;

    @Autowired
    private ArtifactMapper artifactMapper;

    @Test
    void originalReplayCreatesIndependentRunFromFrozenSnapshotAndInputs() {
        Fixture fixture = fixture(snapshot("v5", true), "FixtureAgent", "v1", true);

        WorkflowRun replay = replayService.replay(
                fixture.run().getId(),
                new WorkflowReplayService.ReplayCommand(
                        "ORIGINAL_SNAPSHOT",
                        null,
                        "original-" + UUID.randomUUID()
                )
        );

        assertThat(replay.getId()).isNotEqualTo(fixture.run().getId());
        assertThat(replay.getReplayOfRunId()).isEqualTo(fixture.run().getId());
        assertThat(replay.getWorkflowSnapshotJson()).isEqualTo(fixture.run().getWorkflowSnapshotJson());
        assertThat(replay.getStatus()).isEqualTo("RUNNING");
        assertThat(nodes(replay.getId()))
                .extracting(
                        WorkflowNodeRun::getNodeId,
                        WorkflowNodeRun::getInputJson,
                        WorkflowNodeRun::getStatus
                )
                .containsExactly(
                        Tuple.tuple("author", "{\"requirement\":\"original\"}", "QUEUED"),
                        Tuple.tuple("reviewer", "{\"artifact_version\":1}", "PENDING")
                );
        assertThat(nodes(fixture.run().getId()))
                .extracting(WorkflowNodeRun::getStatus)
                .containsOnly("SUCCEEDED");
        assertThat(artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getProjectId, fixture.project().getId())))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.getContent()).isEqualTo("{\"title\":\"original artifact\"}");
                    assertThat(value.getWorkflowNodeRunId()).isEqualTo(fixture.author().getId());
                });
        assertThat(transitions(replay.getId()))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.getEventType()).isEqualTo("WORKFLOW_REPLAY_CREATED");
                    assertThat(value.getMetadataJson()).contains(
                            "\"source_run_id\":" + fixture.run().getId(),
                            "\"replay_mode\":\"ORIGINAL_SNAPSHOT\""
                    );
                });
    }

    @Test
    void selectedVersionReplayUsesPublishedVersionAndPreservesOriginalInputs() {
        Fixture fixture = fixture(snapshot("v5", true), "FixtureAgent", "v1", false);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey("comparison-" + UUID.randomUUID());
        definition.setName("Replay comparison");
        definition.setStatus("ACTIVE");
        definition.setCreatedAt(LocalDateTime.now());
        definition.setUpdatedAt(LocalDateTime.now());
        definitionMapper.insert(definition);
        WorkflowVersion version = new WorkflowVersion();
        version.setDefinitionId(definition.getId());
        version.setVersion("v6");
        version.setSpecJson(snapshot("v6", true));
        version.setContentHash(UUID.randomUUID().toString());
        version.setStatus("PUBLISHED");
        version.setPublishedAt(LocalDateTime.now());
        version.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(version);

        WorkflowRun replay = replayService.replay(
                fixture.run().getId(),
                new WorkflowReplayService.ReplayCommand(
                        "SELECTED_VERSION",
                        version.getId(),
                        "selected-" + UUID.randomUUID()
                )
        );

        assertThat(replay.getWorkflowVersionId()).isEqualTo(version.getId());
        assertThat(replay.getWorkflowSnapshotJson()).isEqualTo(version.getSpecJson());
        assertThat(replay.getReplayOfRunId()).isEqualTo(fixture.run().getId());
        assertThat(nodes(replay.getId()))
                .extracting(WorkflowNodeRun::getNodeId, WorkflowNodeRun::getInputJson)
                .containsExactly(
                        Tuple.tuple("author", "{\"requirement\":\"original\"}"),
                        Tuple.tuple("reviewer", "{\"artifact_version\":1}")
                );
    }

    @Test
    void originalReplayFailsWithoutSilentlyReplacingUnavailableFrozenHandler() {
        Fixture fixture = fixture(snapshot("v5", false), "RetiredAgent", "v9", false);
        long runCountBefore = runMapper.selectCount(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getProjectId, fixture.project().getId()));

        assertThatThrownBy(() -> replayService.replay(
                fixture.run().getId(),
                new WorkflowReplayService.ReplayCommand(
                        "ORIGINAL_SNAPSHOT",
                        null,
                        "unavailable-" + UUID.randomUUID()
                )
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RUNTIME_VERSION_UNAVAILABLE")
                .hasMessageContaining("RetiredAgent:v9");

        assertThat(runMapper.selectCount(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getProjectId, fixture.project().getId())))
                .isEqualTo(runCountBefore);
    }

    private Fixture fixture(
            String snapshot,
            String handlerKey,
            String handlerVersion,
            boolean withArtifact
    ) {
        Project project = new Project();
        project.setUserId(1L);
        project.setName("replay-" + UUID.randomUUID());
        project.setOriginalRequirement("replay immutable workflow");
        project.setStatus("COMPLETED");
        projectService.save(project);

        LocalDateTime now = LocalDateTime.now();
        WorkflowRun run = new WorkflowRun();
        run.setProjectId(project.getId());
        run.setOperation("GENERATE_V5");
        run.setIdempotencyKey(UUID.randomUUID().toString());
        run.setCorrelationId(UUID.randomUUID().toString());
        run.setWorkflowSnapshotJson(snapshot);
        run.setReviewRound(1);
        run.setMaxReviewRounds(2);
        run.setLockVersion(0);
        run.setStatus("COMPLETED");
        run.setResponseStatus("COMPLETED");
        run.setResponsePercent(100);
        run.setStartedAt(now.minusMinutes(2));
        run.setCompletedAt(now);
        run.setCreatedAt(now.minusMinutes(2));
        run.setUpdatedAt(now);
        runMapper.insert(run);

        WorkflowNodeRun author = node(
                run,
                "author",
                handlerKey,
                handlerVersion,
                "{\"requirement\":\"original\"}"
        );
        if (snapshot.contains("reviewer")) {
            node(
                    run,
                    "reviewer",
                    handlerKey,
                    handlerVersion,
                    "{\"artifact_version\":1}"
            );
        }
        if (withArtifact) {
            Artifact artifact = new Artifact();
            artifact.setProjectId(project.getId());
            artifact.setType("PRD");
            artifact.setTitle("Original artifact");
            artifact.setContent("{\"title\":\"original artifact\"}");
            artifact.setFormat("JSON");
            artifact.setVersion(1);
            artifact.setStatus("APPROVED");
            artifact.setSourceAgent(handlerKey);
            artifact.setWorkflowNodeRunId(author.getId());
            artifactMapper.insert(artifact);
        }
        return new Fixture(project, run, author);
    }

    private WorkflowNodeRun node(
            WorkflowRun run,
            String nodeId,
            String handlerKey,
            String handlerVersion,
            String inputJson
    ) {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setWorkflowRunId(run.getId());
        node.setNodeId(nodeId);
        node.setRevision(1);
        node.setAttempt(1);
        node.setExecutionId(run.getId() + ":" + nodeId + ":1:1");
        node.setStatus("SUCCEEDED");
        node.setHandlerKey(handlerKey);
        node.setHandlerVersion(handlerVersion);
        node.setTimeoutMs(30000);
        node.setInputJson(inputJson);
        node.setOutputJson("{\"ok\":true}");
        node.setLockVersion(0);
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        nodeRunMapper.insert(node);
        return node;
    }

    private List<WorkflowNodeRun> nodes(long runId) {
        return nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                .orderByAsc(WorkflowNodeRun::getNodeId)
                .orderByAsc(WorkflowNodeRun::getRevision)
                .orderByAsc(WorkflowNodeRun::getAttempt));
    }

    private List<WorkflowTransition> transitions(long runId) {
        return transitionMapper.selectList(new LambdaQueryWrapper<WorkflowTransition>()
                .eq(WorkflowTransition::getWorkflowRunId, runId)
                .orderByAsc(WorkflowTransition::getId));
    }

    private String snapshot(String version, boolean withReviewer) {
        String reviewer = withReviewer
                ? ",{\"node_id\":\"reviewer\",\"depends_on\":[\"author\"]}"
                : "";
        return """
                {
                  "workflow_key":"replay-test",
                  "version":"%s",
                  "runtime":{"max_parallel_nodes":2},
                  "nodes":[{"node_id":"author","depends_on":[]}%s],
                  "edges":[]
                }
                """.formatted(version, reviewer);
    }

    private record Fixture(Project project, WorkflowRun run, WorkflowNodeRun author) {
    }
}
