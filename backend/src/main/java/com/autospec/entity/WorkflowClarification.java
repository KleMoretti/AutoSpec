package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_clarification")
public class WorkflowClarification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workflowRunId;
    private Long nodeRunId;
    private Integer revision;
    private Integer round;
    private String requestId;
    private String requestJson;
    private String responseJson;
    private String status;
    private Long approvalId;
    private Integer lockVersion = 0;
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime answeredAt;
    private LocalDateTime expiredAt;
    private LocalDateTime updatedAt;
}
