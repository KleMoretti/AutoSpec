package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable idempotency and audit fact for one controlled tool request. */
@Data
@TableName("workflow_tool_call_fact")
public class WorkflowToolCallFact {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private String idempotencyKey;
    private String executionId;
    private Long workflowRunId;
    private Long nodeRunId;
    private String nodeId;
    private Long actorUserId;
    private Long projectId;
    private Long fencingToken;
    private Long deadlineEpochMs;
    private String executionBundleHash;
    private String policyHash;
    private String name;
    private String version;
    private String normalizedParamsHash;
    private String status;
    private String resultJson;
    private String resultHash;
    private String errorCode;
    private String errorMessage;
    private Boolean cached;
    private String sourceExecutionId;
    private Integer attempts;
    private Integer durationMs;
    private String correlationId;
    private String traceparent;
    private String tracestate;
    private LocalDateTime createdAt;
}
