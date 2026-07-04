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

    private String providerKey;

    private String modelName;

    private String agentNode;

    private Long promptVersionId;

    private String status;

    private Integer durationMs;

    private Integer inputTokens;

    private Integer outputTokens;

    private BigDecimal estimatedCost;

    private BigDecimal score;

    private String errorMessage;

    private LocalDateTime createdAt;
}
