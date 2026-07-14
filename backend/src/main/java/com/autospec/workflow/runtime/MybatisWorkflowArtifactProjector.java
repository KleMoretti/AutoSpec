package com.autospec.workflow.runtime;

import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MybatisWorkflowArtifactProjector implements WorkflowArtifactProjector {
    private final ArtifactMapper artifactMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;

    public MybatisWorkflowArtifactProjector(
            ArtifactMapper artifactMapper,
            WorkflowRunMapper runMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler
    ) {
        this.artifactMapper = artifactMapper;
        this.runMapper = runMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
    }

    @Override
    public Artifact project(WorkflowNodeRun nodeRun, String outputJson, String status) {
        Artifact existing = artifactMapper.selectOne(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getWorkflowNodeRunId, nodeRun.getId())
                .orderByDesc(Artifact::getId)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        WorkflowRun run = runMapper.selectById(nodeRun.getWorkflowRunId());
        if (run == null) {
            throw new IllegalStateException("workflow run not found: " + nodeRun.getWorkflowRunId());
        }
        WorkflowNodeDocument spec = dagCompiler.compile(
                snapshotParser.parse(run.getWorkflowSnapshotJson())
        ).nodes().get(nodeRun.getNodeId());
        if (spec == null || spec.artifactType() == null || spec.artifactType().isBlank()) {
            return null;
        }
        Artifact latest = artifactMapper.selectOne(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getProjectId, run.getProjectId())
                .eq(Artifact::getType, spec.artifactType())
                .orderByDesc(Artifact::getVersion)
                .orderByDesc(Artifact::getId)
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        Artifact artifact = new Artifact();
        artifact.setProjectId(run.getProjectId());
        artifact.setType(spec.artifactType());
        artifact.setTitle(title(spec.artifactType()));
        artifact.setContent(outputJson == null ? "{}" : outputJson);
        artifact.setFormat("JSON");
        artifact.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        artifact.setStatus(status);
        artifact.setSourceAgent(nodeRun.getHandlerKey());
        artifact.setWorkflowNodeRunId(nodeRun.getId());
        artifact.setCreatedAt(now);
        artifact.setUpdatedAt(now);
        artifactMapper.insert(artifact);
        return artifact;
    }

    private String title(String artifactType) {
        String normalized = artifactType.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
