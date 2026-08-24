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

    private String routeKey;

    private String routeReason;

    private Boolean fallbackUsed;

    private String contextManifestJson;

    private Integer callCount;

    private BigDecimal estimatedCost;

    private BigDecimal score;

    private String errorMessage;

    private LocalDateTime createdAt;
}
