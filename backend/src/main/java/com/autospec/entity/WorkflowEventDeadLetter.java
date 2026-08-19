package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_event_dead_letter")
public class WorkflowEventDeadLetter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String streamMessageId;
    private String payloadJson;
    private String errorType;
    private String errorMessage;
    private String status;
    private LocalDateTime replayedAt;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
}
