package com.autospec.service.impl;

import com.autospec.entity.AuditEvent;
import com.autospec.mapper.AuditEventMapper;
import com.autospec.service.AuditEventService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class AuditEventServiceImpl extends ServiceImpl<AuditEventMapper, AuditEvent> implements AuditEventService {

    @Override
    public void record(
            Long projectId,
            Long actorUserId,
            String eventType,
            String entityType,
            Long entityId,
            String message,
            String metadata
    ) {
        AuditEvent event = new AuditEvent();
        event.setProjectId(projectId);
        event.setActorUserId(actorUserId);
        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setMessage(message);
        event.setMetadata(metadata);
        save(event);
    }
}
