package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable projection of one bounded agent-loop step. */
@Data
@TableName("workflow_agent_step_fact")
public class WorkflowAgentStepFact {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workflowRunId;
    private Long nodeRunId;
    private String nodeId;
    private Integer revision;
    private Integer attempt;
    private String executionId;
    private String contractHash;
    private Integer step;
    private String phase;
    private String status;
    private String reasonCode;
    private String planHash;
    private String observationHash;
    private String validationIssueCodesJson;
    private String modelCallRef;
    private String toolCallRef;
    private Long startedAtEpochMs;
    private Long finishedAtEpochMs;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
