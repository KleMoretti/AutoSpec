package com.autospec.service;

import com.autospec.entity.ExternalCallLog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;

public interface ExternalCallLogService extends IService<ExternalCallLog> {

    void record(
            Long projectId,
            String targetService,
            String operation,
            String status,
            Integer durationMs,
            String requestContext,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    );
}
