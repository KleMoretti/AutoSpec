package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("workflow_run")
public class WorkflowRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long initiatedByUserId;

    private String operation;

    private String idempotencyKey;

    private String correlationId;

    private Long workflowVersionId;

    private String workflowSnapshotJson;

    private Long replayOfRunId;

    private Integer reviewRound;

    private Integer maxReviewRounds;

    private Integer acceptedDuplicateEventCount;

    private String qualityProfile;

    private Long maxTokens;

    private BigDecimal maxCost;

    private Integer maxModelCalls;

    private Long maxWallTimeMs;

    private Long consumedTokens;

    private BigDecimal consumedCost;

    private Integer modelCallCount;

    private Long reservedTokens;

    private BigDecimal reservedCost;

    private Integer reservedModelCalls;

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
