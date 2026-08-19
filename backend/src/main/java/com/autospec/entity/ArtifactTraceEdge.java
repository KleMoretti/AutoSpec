package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("artifact_trace_edge")
public class ArtifactTraceEdge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long workflowRunId;
    private Long sourceArtifactId;
    private String fromComponentKey;
    private String toComponentKey;
    private String relationType;
    private LocalDateTime createdAt;
}
