package com.autospec.service.impl;

import com.autospec.entity.ExternalCallLog;
import com.autospec.mapper.ExternalCallLogMapper;
import com.autospec.service.ExternalCallLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ExternalCallLogServiceImpl extends ServiceImpl<ExternalCallLogMapper, ExternalCallLog> implements ExternalCallLogService {

    @Override
    public List<ExternalCallLog> listByProjectId(Long projectId, int limit, int offset) {
        return lambdaQuery()
                .eq(ExternalCallLog::getProjectId, projectId)
                .orderByAsc(ExternalCallLog::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long projectId,
            String targetService,
            String operation,
            String status,
            Integer durationMs,
            String requestContext,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
        record(projectId, targetService, null, operation, status, durationMs, requestContext, errorMessage, startedAt, completedAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long projectId,
            String targetService,
            String correlationId,
            String operation,
            String status,
            Integer durationMs,
            String requestContext,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
        ExternalCallLog log = new ExternalCallLog();
        log.setProjectId(projectId);
        log.setTargetService(targetService);
        log.setCorrelationId(correlationId);
        log.setOperation(operation);
        log.setStatus(status);
        log.setDurationMs(durationMs);
        log.setRequestContext(requestContext);
        log.setErrorMessage(errorMessage);
        log.setStartedAt(startedAt);
        log.setCompletedAt(completedAt);
        save(log);
    }
}
