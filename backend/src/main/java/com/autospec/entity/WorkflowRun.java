package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_run")
public class WorkflowRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String operation;

    private String idempotencyKey;

    private String correlationId;

    private Long workflowVersionId;

    private String workflowSnapshotJson;

    private Long replayOfRunId;

    private Integer reviewRound;

    private Integer maxReviewRounds;

    private Integer acceptedDuplicateEventCount;

    private Integer lockVersion;

    private LocalDateTime lastHeartbeatAt;

    private String status;

    private String responseStatus;

    private Integer responsePercent;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
