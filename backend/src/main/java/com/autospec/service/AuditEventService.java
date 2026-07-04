package com.autospec.service;

import com.autospec.entity.AuditEvent;
import com.baomidou.mybatisplus.extension.service.IService;

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
}
