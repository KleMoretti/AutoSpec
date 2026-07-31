package com.autospec.service;

import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.exception.OptimisticLockConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ArtifactVersionService {

    private final ArtifactService artifactService;
    private final ProjectService projectService;
    private final KnowledgeIndexService knowledgeIndexService;

    public ArtifactVersionService(
            ArtifactService artifactService,
            ProjectService projectService,
            KnowledgeIndexService knowledgeIndexService
    ) {
        this.artifactService = artifactService;
        this.projectService = projectService;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Artifact updateDraft(Long projectId, Long artifactId, String content, int expectedLockVersion) {
        Artifact current = requireProjectArtifact(projectId, artifactId);
        Artifact latest = latestVersion(projectId, current.getType());
        if (!current.getId().equals(latest.getId())) {
            throw conflict(current, expectedLockVersion, latest);
        }

        boolean claimed = artifactService.lambdaUpdate()
                .eq(Artifact::getId, artifactId)
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getLockVersion, expectedLockVersion)
                .setSql("lock_version = lock_version + 1")
                .set(Artifact::getUpdatedAt, LocalDateTime.now())
                .update();
        if (!claimed) {
            Artifact refreshed = requireProjectArtifact(projectId, artifactId);
            throw conflict(refreshed, expectedLockVersion, latestVersion(projectId, current.getType()));
        }

        Artifact next = new Artifact();
        next.setProjectId(projectId);
        next.setType(current.getType());
        next.setTitle(current.getTitle());
        next.setContent(content);
        next.setFormat(current.getFormat());
        next.setVersion(latest.getVersion() + 1);
        next.setLockVersion(0);
        next.setStatus("PENDING_REVIEW");
        next.setSourceAgent("HUMAN_EDITOR");
        next.setParentArtifactId(current.getId());
        try {
            artifactService.save(next);
        } catch (DuplicateKeyException duplicateVersion) {
            Artifact refreshed = requireProjectArtifact(projectId, artifactId);
            throw conflict(refreshed, expectedLockVersion, latestVersion(projectId, current.getType()));
        }
        return next;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Artifact approve(Long projectId, Long artifactId) {
        Artifact artifact = requireProjectArtifact(projectId, artifactId);
        Artifact latest = latestVersion(projectId, artifact.getType());
        if (!artifact.getId().equals(latest.getId())) {
            throw conflict(artifact, artifact.getLockVersion(), latest);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean approved = artifactService.lambdaUpdate()
                .eq(Artifact::getId, artifactId)
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getLockVersion, artifact.getLockVersion())
                .ne(Artifact::getStatus, "APPROVED")
                .set(Artifact::getStatus, "APPROVED")
                .set(Artifact::getApprovedAt, now)
                .set(Artifact::getUpdatedAt, now)
                .setSql("lock_version = lock_version + 1")
                .update();
        if (!approved) {
            Artifact refreshed = requireProjectArtifact(projectId, artifactId);
            throw conflict(refreshed, artifact.getLockVersion(), latestVersion(projectId, artifact.getType()));
        }
        artifact.setStatus("APPROVED");
        artifact.setApprovedAt(now);
        artifact.setUpdatedAt(now);
        artifact.setLockVersion(artifact.getLockVersion() + 1);
        knowledgeIndexService.indexApprovedArtifact(artifact);
        if ("PRD".equals(artifact.getType())) {
            Project project = requireProject(projectId);
            project.setStatus("PRD_APPROVED");
            projectService.updateById(project);
        }
        return artifact;
    }

    public Artifact latestApproved(Long projectId, String type) {
        return artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, type)
                .eq(Artifact::getStatus, "APPROVED")
                .orderByDesc(Artifact::getVersion)
                .last("limit 1")
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Approved artifact not found: " + type));
    }

    public List<Artifact> listVersions(Long projectId, Long artifactId, int limit, int offset) {
        Artifact artifact = requireProjectArtifact(projectId, artifactId);
        return artifactService.listVersionsByProjectIdAndType(projectId, artifact.getType(), limit, offset);
    }

    public Artifact requireProjectArtifact(Long projectId, Long artifactId) {
        Artifact artifact = artifactService.getById(artifactId);
        if (artifact == null || !projectId.equals(artifact.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found");
        }
        return artifact;
    }

    private Artifact latestVersion(Long projectId, String type) {
        return artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, type)
                .orderByDesc(Artifact::getVersion)
                .orderByDesc(Artifact::getId)
                .last("limit 1")
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found"));
    }

    private OptimisticLockConflictException conflict(
            Artifact current,
            int expectedLockVersion,
            Artifact latest
    ) {
        return new OptimisticLockConflictException(
                "artifact",
                current.getId(),
                expectedLockVersion,
                current.getLockVersion(),
                latest.getId(),
                latest.getVersion()
        );
    }

    private Project requireProject(Long projectId) {
        Project project = projectService.getById(projectId);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return project;
    }
}
