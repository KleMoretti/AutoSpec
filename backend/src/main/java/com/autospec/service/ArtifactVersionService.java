package com.autospec.service;

import com.autospec.dto.ArtifactDiffResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.exception.OptimisticLockConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
public class ArtifactVersionService {

    private final ArtifactService artifactService;
    private final ProjectService projectService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final ObjectMapper objectMapper;
    private final ArtifactTraceGraphService traceGraphService;

    @Autowired
    public ArtifactVersionService(
            ArtifactService artifactService,
            ProjectService projectService,
            KnowledgeIndexService knowledgeIndexService,
            ObjectMapper objectMapper,
            ArtifactTraceGraphService traceGraphService
    ) {
        this.artifactService = artifactService;
        this.projectService = projectService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.objectMapper = objectMapper;
        this.traceGraphService = traceGraphService;
    }

    public ArtifactVersionService(
            ArtifactService artifactService,
            ProjectService projectService,
            KnowledgeIndexService knowledgeIndexService,
            ObjectMapper objectMapper
    ) {
        this(artifactService, projectService, knowledgeIndexService, objectMapper, null);
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
        next.setWorkflowNodeRunId(current.getWorkflowNodeRunId());
        copyProvenance(current, next);
        next.setContentHash(contentHash(content));
        try {
            artifactService.save(next);
        } catch (DuplicateKeyException duplicateVersion) {
            Artifact refreshed = requireProjectArtifact(projectId, artifactId);
            throw conflict(refreshed, expectedLockVersion, latestVersion(projectId, current.getType()));
        }
        projectTrace(next);
        return next;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Artifact restore(
            Long projectId,
            Long sourceArtifactId,
            int expectedLatestLockVersion
    ) {
        Artifact source = requireProjectArtifact(projectId, sourceArtifactId);
        Artifact latest = latestVersion(projectId, source.getType());
        boolean claimed = artifactService.lambdaUpdate()
                .eq(Artifact::getId, latest.getId())
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getLockVersion, expectedLatestLockVersion)
                .setSql("lock_version = lock_version + 1")
                .set(Artifact::getUpdatedAt, LocalDateTime.now())
                .update();
        if (!claimed) {
            throw conflict(
                    latest,
                    expectedLatestLockVersion,
                    latestVersion(projectId, source.getType())
            );
        }

        Artifact restored = new Artifact();
        restored.setProjectId(projectId);
        restored.setType(source.getType());
        restored.setTitle(source.getTitle());
        restored.setContent(source.getContent());
        restored.setFormat(source.getFormat());
        restored.setVersion(latest.getVersion() + 1);
        restored.setLockVersion(0);
        restored.setStatus("PENDING_REVIEW");
        restored.setSourceAgent("HUMAN_RESTORE");
        restored.setParentArtifactId(source.getId());
        restored.setWorkflowNodeRunId(source.getWorkflowNodeRunId());
        copyProvenance(source, restored);
        restored.setContentHash(contentHash(source.getContent()));
        try {
            artifactService.save(restored);
        } catch (DuplicateKeyException duplicateVersion) {
            throw conflict(
                    latest,
                    expectedLatestLockVersion,
                    latestVersion(projectId, source.getType())
            );
        }
        projectTrace(restored);
        return restored;
    }

    public ArtifactDiffResponse diff(Long projectId, Long baseArtifactId, Long targetArtifactId) {
        Artifact base = requireProjectArtifact(projectId, baseArtifactId);
        Artifact target = requireProjectArtifact(projectId, targetArtifactId);
        if (!base.getType().equals(target.getType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Artifacts must have the same type");
        }
        Set<String> changedPaths = new TreeSet<>();
        try {
            collectChangedPaths(
                    objectMapper.readTree(base.getContent()),
                    objectMapper.readTree(target.getContent()),
                    "$",
                    changedPaths
            );
        } catch (Exception invalidJson) {
            if (!java.util.Objects.equals(base.getContent(), target.getContent())) {
                changedPaths.add("$");
            }
        }
        return new ArtifactDiffResponse(
                baseArtifactId,
                targetArtifactId,
                !changedPaths.isEmpty(),
                List.copyOf(changedPaths)
        );
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

    private void copyProvenance(Artifact source, Artifact target) {
        target.setSchemaVersion(source.getSchemaVersion());
        target.setPromptKey(source.getPromptKey());
        target.setPromptVersion(source.getPromptVersion());
        target.setModelProvider(source.getModelProvider());
        target.setModelName(source.getModelName());
        target.setSourceCitationsJson(source.getSourceCitationsJson());
        target.setProvenanceJson(source.getProvenanceJson());
    }

    private void projectTrace(Artifact artifact) {
        if (traceGraphService != null) {
            traceGraphService.project(artifact);
        }
    }

    private String contentHash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void collectChangedPaths(
            JsonNode base,
            JsonNode target,
            String path,
            Set<String> changedPaths
    ) {
        if (java.util.Objects.equals(base, target)) {
            return;
        }
        if (base == null || target == null || base.getNodeType() != target.getNodeType()) {
            changedPaths.add(path);
            return;
        }
        if (base.isObject()) {
            Set<String> names = new TreeSet<>();
            base.fieldNames().forEachRemaining(names::add);
            target.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                collectChangedPaths(base.get(name), target.get(name), path + "." + name, changedPaths);
            }
            return;
        }
        if (base.isArray()) {
            int length = Math.max(base.size(), target.size());
            for (int index = 0; index < length; index++) {
                collectChangedPaths(base.get(index), target.get(index), path + "[" + index + "]", changedPaths);
            }
            return;
        }
        changedPaths.add(path);
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
