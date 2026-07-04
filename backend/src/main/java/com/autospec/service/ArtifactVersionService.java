package com.autospec.service;

import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    @Transactional
    public Artifact updateDraft(Long projectId, Long artifactId, String content) {
        Artifact current = requireProjectArtifact(projectId, artifactId);
        Artifact next = new Artifact();
        next.setProjectId(projectId);
        next.setType(current.getType());
        next.setTitle(current.getTitle());
        next.setContent(content);
        next.setFormat(current.getFormat());
        next.setVersion(nextVersion(projectId, current.getType()));
        next.setStatus("PENDING_REVIEW");
        next.setSourceAgent("HUMAN_EDITOR");
        next.setParentArtifactId(current.getId());
        artifactService.save(next);
        return next;
    }

    @Transactional
    public Artifact approve(Long projectId, Long artifactId) {
        Artifact artifact = requireProjectArtifact(projectId, artifactId);
        artifact.setStatus("APPROVED");
        artifact.setApprovedAt(LocalDateTime.now());
        artifactService.updateById(artifact);
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

    private int nextVersion(Long projectId, String type) {
        return artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, type)
                .list()
                .stream()
                .map(Artifact::getVersion)
                .filter(version -> version != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private Project requireProject(Long projectId) {
        Project project = projectService.getById(projectId);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return project;
    }
}
