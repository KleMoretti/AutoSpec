package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_event")
public class AuditEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long actorUserId;

    private String eventType;

    private String entityType;

    private Long entityId;

    private String message;

    private String metadata;

    private LocalDateTime createdAt;
}
