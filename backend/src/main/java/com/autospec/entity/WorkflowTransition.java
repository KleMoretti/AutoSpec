package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_transition")
public class WorkflowTransition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workflowRunId;
    private Long nodeRunId;
    private String fromStatus;
    private String toStatus;
    private String eventType;
    private String eventId;
    private String metadataJson;
    private LocalDateTime createdAt;
}
