package com.autospec.service;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.KnowledgeChunk;
import com.autospec.entity.KnowledgeDocument;
import com.autospec.entity.Project;
import com.autospec.entity.ProjectMember;
import com.autospec.entity.UserAccount;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.util.ContentHash;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class HarnessH0KnowledgeTest {
    @Autowired
    private KnowledgeIndexService knowledgeIndexService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeChunkService knowledgeChunkService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ArtifactVersionService artifactVersionService;

    @Autowired
    private ArtifactApprovalOutboxService approvalOutboxService;

    @Autowired
    private WorkflowOutboxMapper outboxMapper;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectMemberService projectMemberService;

    @Autowired
    private UserAccountService userAccountService;

    @Test
    void retrievalIsProjectAndActorScopedAndOnlyUsesLatestActiveVersion() {
        UserAccount owner = user("owner");
        UserAccount collaborator = user("collaborator");
        Project firstProject = project(owner, "first");
        Project secondProject = project(owner, "second");
        member(firstProject, owner, "OWNER");
        member(firstProject, collaborator, "VIEWER");
        member(secondProject, owner, "OWNER");

        Artifact firstV1 = approvedArtifact(
                firstProject, 1, "legacy clinic scheduling sentinel"
        );
        Artifact firstV2 = approvedArtifact(
                firstProject, 2, "current clinic scheduling sentinel"
        );
        Artifact second = approvedArtifact(
                secondProject, 1, "secret clinic scheduling sentinel"
        );

        knowledgeIndexService.indexApprovedArtifact(firstV1);
        knowledgeIndexService.indexApprovedArtifact(firstV2);
        knowledgeIndexService.indexApprovedArtifact(second);
        int activeChunkCount = knowledgeChunkService.lambdaQuery()
                .eq(com.autospec.entity.KnowledgeChunk::getDocumentId,
                        document(firstV2).getId())
                .count().intValue();
        knowledgeIndexService.indexApprovedArtifact(firstV2);

        List<KnowledgeSourceResponse> ownerSources = knowledgeIndexService.retrieveForProject(
                "clinic scheduling sentinel", 10, firstProject.getId(), owner.getId()
        );
        List<KnowledgeSourceResponse> collaboratorSources = knowledgeIndexService.retrieveForProject(
                "clinic scheduling sentinel", 10, firstProject.getId(), collaborator.getId()
        );

        assertThat(ownerSources).isNotEmpty().allSatisfy(source -> {
            assertThat(source.projectId()).isEqualTo(firstProject.getId());
            assertThat(source.artifactId()).isEqualTo(firstV2.getId());
            assertThat(source.artifactContentHash()).isEqualTo(firstV2.getContentHash());
            assertThat(source.chunkContentHash()).hasSize(64);
            assertThat(source.chunkerVersion()).isEqualTo(KnowledgeIndexService.CHUNKER_VERSION);
            assertThat(source.embeddingModel()).isEqualTo(KnowledgeEmbeddingService.MODEL_VERSION);
        });
        assertThat(collaboratorSources).extracting(KnowledgeSourceResponse::artifactId)
                .containsOnly(firstV2.getId());
        assertThat(knowledgeIndexService.retrieveForProject(
                "secret clinic scheduling sentinel",
                10,
                secondProject.getId(),
                collaborator.getId()
        )).isEmpty();
        assertThat(document(firstV1).getStatus()).isEqualTo("SUPERSEDED");
        assertThat(document(firstV2).getStatus()).isEqualTo("ACTIVE");
        assertThat(document(second).getStatus()).isEqualTo("ACTIVE");
        assertThat(knowledgeChunkService.lambdaQuery()
                .eq(com.autospec.entity.KnowledgeChunk::getDocumentId,
                        document(firstV2).getId())
                .count().intValue()).isEqualTo(activeChunkCount);

        KnowledgeChunk corrupt = knowledgeChunkService.lambdaQuery()
                .eq(KnowledgeChunk::getDocumentId, document(firstV2).getId())
                .orderByAsc(KnowledgeChunk::getChunkIndex)
                .last("limit 1")
                .one();
        corrupt.setEmbeddingJson("{corrupt");
        knowledgeChunkService.updateById(corrupt);
        KnowledgeIndexRecoveryJob recoveryJob = new KnowledgeIndexRecoveryJob(
                knowledgeDocumentService,
                artifactService,
                knowledgeIndexService,
                approvalOutboxService,
                100
        );

        assertThat(recoveryJob.scanAndEnqueue()).isEqualTo(1);
        assertThat(document(firstV2).getStatus()).isEqualTo("FAILED");
        assertThat(eventsFor(firstV2.getId())).hasSize(1);
        knowledgeIndexService.indexApprovedArtifact(firstV2);
        assertThat(document(firstV2).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void ordinaryAndRestoredApprovalsPublishIdempotentIndexEvents() {
        UserAccount owner = user("approval-owner");
        Project project = project(owner, "approval-events");
        member(project, owner, "OWNER");
        Artifact draft = artifact(
                project,
                1,
                "{\"project_name\":\"Restorable\",\"core_features\":[],\"user_stories\":[]}",
                "PENDING_REVIEW"
        );

        Artifact approved = artifactVersionService.approve(project.getId(), draft.getId());
        approvalOutboxService.enqueue(approved);

        assertThat(eventsFor(approved.getId()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo("ARTIFACT_APPROVED");
                    assertThat(event.getStatus()).isEqualTo("PENDING");
                    assertThat(event.getPayloadJson())
                            .contains(approved.getContentHash())
                            .contains(KnowledgeIndexService.CHUNKER_VERSION)
                            .contains(KnowledgeEmbeddingService.MODEL_VERSION);
                });

        Artifact restored = artifactVersionService.restore(
                project.getId(), approved.getId(), approved.getLockVersion()
        );
        Artifact approvedRestore = artifactVersionService.approve(
                project.getId(), restored.getId()
        );

        assertThat(approvedRestore.getParentArtifactId()).isEqualTo(approved.getId());
        assertThat(eventsFor(approvedRestore.getId())).hasSize(1);
    }

    private UserAccount user(String prefix) {
        UserAccount user = new UserAccount();
        user.setUsername(prefix + "-" + UUID.randomUUID());
        user.setDisplayName(prefix);
        user.setPasswordHash("test-only-password-hash");
        user.setEnabled(true);
        userAccountService.save(user);
        return user;
    }

    private Project project(UserAccount owner, String prefix) {
        Project project = new Project();
        project.setUserId(owner.getId());
        project.setName(prefix + "-" + UUID.randomUUID());
        project.setOriginalRequirement("test project-scoped knowledge");
        project.setStatus("GENERATING");
        projectService.save(project);
        return project;
    }

    private void member(Project project, UserAccount user, String role) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(project.getId());
        member.setUserId(user.getId());
        member.setRole(role);
        projectMemberService.save(member);
    }

    private Artifact approvedArtifact(Project project, int version, String content) {
        return artifact(project, version, content, "APPROVED");
    }

    private Artifact artifact(Project project, int version, String content, String status) {
        Artifact artifact = new Artifact();
        artifact.setProjectId(project.getId());
        artifact.setType("PRD");
        artifact.setTitle("Knowledge PRD v" + version);
        artifact.setContent(content);
        artifact.setFormat("MARKDOWN");
        artifact.setVersion(version);
        artifact.setLockVersion(0);
        artifact.setStatus(status);
        artifact.setSourceAgent("test");
        artifact.setContentHash(ContentHash.sha256(content));
        artifactService.save(artifact);
        return artifact;
    }

    private KnowledgeDocument document(Artifact artifact) {
        return knowledgeDocumentService.lambdaQuery()
                .eq(KnowledgeDocument::getArtifactId, artifact.getId())
                .one();
    }

    private List<WorkflowOutbox> eventsFor(Long artifactId) {
        return outboxMapper.selectList(new LambdaQueryWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getAggregateId, "artifact:" + artifactId)
                .eq(WorkflowOutbox::getEventType, "ARTIFACT_APPROVED"));
    }
}
