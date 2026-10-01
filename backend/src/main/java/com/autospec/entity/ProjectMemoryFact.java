package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project_memory_fact")
public class ProjectMemoryFact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String factType;

    private String factKey;

    private String valueJson;

    private String contentHash;

    private Integer version;

    private String conflictStatus;
    private String trustStatus;

    private String sourceType;

    private String sourceRef;

    private Long sourceWorkflowRunId;

    private Long sourceNodeRunId;

    private Long sourceArtifactId;

    private String sourceArtifactType;

    private Integer sourceArtifactVersion;

    private LocalDateTime validFromAt;

    private LocalDateTime validUntilAt;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
