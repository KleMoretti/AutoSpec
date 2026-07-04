package com.autospec.service;

import com.autospec.entity.AuditEvent;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface AuditEventService extends IService<AuditEvent> {

    void record(
            Long projectId,
            Long actorUserId,
            String eventType,
            String entityType,
            Long entityId,
            String message,
            String metadata
    );

    List<AuditEvent> listByProjectId(Long projectId, int limit, int offset);
}
