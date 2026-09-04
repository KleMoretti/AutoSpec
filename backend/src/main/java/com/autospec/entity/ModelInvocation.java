package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("model_invocation")
public class ModelInvocation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long taskId;

    private Long workflowRunId;

    private Long workflowNodeRunId;

    private String callId;

    private String callType;

    private String executionId;

    private Integer callSequence;

    private Integer attempt;

    private String correlationId;

    private String providerKey;

    private String modelName;

    private String agentNode;

    private Long promptVersionId;

    private String status;

    private Integer durationMs;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer cacheTokens;

    private String promptKey;

    private String promptVersion;

    private String promptChecksum;

    private String contractHash;

    private String schemaVersion;

    private String routeKey;

    private String routeReason;

    private Boolean fallbackUsed;

    private String contextManifestJson;

    private Integer callCount;

    private Integer reservedInputTokens;

    private Integer reservedOutputTokens;

    private BigDecimal reservedCost;

    private Integer settlementDeltaTokens;

    private BigDecimal settlementDeltaCost;

    private String normalizedParamsHash;

    private String resultHash;

    private String errorCode;

    private Long deadlineEpochMs;

    private String idempotencyKey;

    private String toolName;

    private String toolVersion;

    private String permissionPolicy;

    private String referenceSourcesJson;

    private String redactedParamsJson;

    private BigDecimal estimatedCost;

    private BigDecimal score;

    private String errorMessage;

    private LocalDateTime createdAt;
}
