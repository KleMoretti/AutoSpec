package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_task")
public class AgentTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String agentName;

    private String nodeName;

    private String status;

    private String inputText;

    private String outputText;

    private String errorMessage;

    private Integer durationMs;

    private Long retryOfTaskId;

    private Long promptVersionId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createdAt;
}
