package com.autospec.service;

import com.autospec.entity.ExternalCallLog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;
import java.util.List;

public interface ExternalCallLogService extends IService<ExternalCallLog> {

    List<ExternalCallLog> listByProjectId(Long projectId, int limit, int offset);

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
