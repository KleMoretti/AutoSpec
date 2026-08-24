package com.autospec.integration;

import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.exception.OptimisticLockConflictException;
import com.autospec.service.ArtifactService;
import com.autospec.service.ArtifactVersionService;
import com.autospec.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration-test")
class ArtifactOptimisticLockIT extends MySqlIntegrationTestSupport {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ArtifactVersionService artifactVersionService;

    @Test
    void concurrentEditorsCreateExactlyOneChildVersion() throws Exception {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("Artifact optimistic lock integration");
        project.setOriginalRequirement("Protect concurrent edits.");
        project.setStatus("CREATED");
        projectService.save(project);

        Artifact original = new Artifact();
        original.setProjectId(project.getId());
        original.setType("PRD");
        original.setTitle("Concurrent PRD");
        original.setContent("{\"project_name\":\"original\"}");
        original.setFormat("JSON");
        original.setVersion(1);
        original.setStatus("GENERATED");
        artifactService.save(original);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<EditOutcome> first = executor.submit(() -> edit(
                    project.getId(), original.getId(), "{\"editor\":1}", ready, start
            ));
            Future<EditOutcome> second = executor.submit(() -> edit(
                    project.getId(), original.getId(), "{\"editor\":2}", ready, start
            ));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<EditOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertThat(outcomes).extracting(EditOutcome::status)
                    .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
            EditOutcome conflict = outcomes.stream()
                    .filter(outcome -> outcome.conflict() != null)
                    .findFirst()
                    .orElseThrow();
            assertThat(conflict.conflict().getDetails())
                    .containsEntry("expectedLockVersion", "0")
                    .containsEntry("currentLockVersion", "1")
                    .containsEntry("latestResourceVersion", "2");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        Artifact persistedOriginal = artifactService.getById(original.getId());
        List<Artifact> versions = artifactService.listVersionsByProjectIdAndType(
                project.getId(), "PRD", 10, 0
        );
        assertThat(persistedOriginal.getLockVersion()).isEqualTo(1);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(1).getParentArtifactId()).isEqualTo(original.getId());
    }

    private EditOutcome edit(
            long projectId,
            long artifactId,
            String content,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent edit start timed out");
        }
        try {
            return new EditOutcome(
                    "SUCCESS",
                    artifactVersionService.updateDraft(projectId, artifactId, content, 0),
                    null
            );
        } catch (OptimisticLockConflictException conflict) {
            return new EditOutcome("CONFLICT", null, conflict);
        }
    }

    private record EditOutcome(
            String status,
            Artifact artifact,
            OptimisticLockConflictException conflict
    ) {
    }
}
