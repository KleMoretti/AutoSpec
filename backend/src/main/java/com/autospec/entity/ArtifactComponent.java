package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("artifact_component")
public class ArtifactComponent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long workflowRunId;
    private Long artifactId;
    private String artifactType;
    private String componentKey;
    private String componentType;
    private String displayName;
    private String jsonPath;
    private String contentHash;
    private LocalDateTime createdAt;
}
