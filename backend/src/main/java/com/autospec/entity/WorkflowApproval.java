package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_approval")
public class WorkflowApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workflowRunId;
    private Long nodeRunId;
    private String mode;
    private String status;
    private String decision;
    private Long candidateArtifactId;
    private Long revisedArtifactId;
    private Long decidedByUserId;
    private String decisionReason;
    private String idempotencyKey;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
