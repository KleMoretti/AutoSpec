package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_event_delivery_attempt")
public class WorkflowEventDeliveryAttempt {
    @TableId
    private String streamMessageId;
    private String consumerGroup;
    private Integer deliveryCount;
    private LocalDateTime lastAttemptAt;
}
