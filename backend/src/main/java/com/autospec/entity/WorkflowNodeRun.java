package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("workflow_node_run")
public class WorkflowNodeRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workflowRunId;
    private String nodeId;
    private Integer revision;
    private Integer attempt;
    private String executionId;
    private String contractHash;
    private String executionBundleHash;
    private Long fencingToken;
    private String budgetReservationId;
    private Long reservedInputTokens;
    private Long reservedOutputTokens;
    private BigDecimal reservedCost;
    private Integer reservedModelCalls;
    private Integer actualInputTokens;
    private Integer actualOutputTokens;
    private Integer actualCacheTokens;
    private BigDecimal actualCost;
    private Integer actualModelCalls;
    private Integer actualToolCalls;
    private String budgetStatus;
    private LocalDateTime budgetSettledAt;
    private String status;
    private String handlerKey;
    private String handlerVersion;
    private Integer timeoutMs;
    private Integer durationMs;
    private String inputJson;
    private String outputJson;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime heartbeatAt;
    private LocalDateTime finishedAt;
    private LocalDateTime nextRetryAt;
    private String workerId;
    private Integer lockVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
