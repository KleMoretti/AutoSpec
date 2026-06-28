package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_event")
public class AgentEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long taskId;

    private String eventType;

    private String nodeName;

    private String message;

    private String payload;

    private LocalDateTime createdAt;
}
