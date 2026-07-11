package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

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
    private String status;
    private String handlerKey;
    private String handlerVersion;
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
