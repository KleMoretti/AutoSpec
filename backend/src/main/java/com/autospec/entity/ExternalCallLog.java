package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("external_call_log")
public class ExternalCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String targetService;

    private String correlationId;

    private String operation;

    private String status;

    private Integer durationMs;

    private String requestContext;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
}
