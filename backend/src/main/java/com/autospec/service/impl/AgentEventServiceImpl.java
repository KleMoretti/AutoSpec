package com.autospec.service.impl;

import com.autospec.entity.AgentEvent;
import com.autospec.mapper.AgentEventMapper;
import com.autospec.service.AgentEventStreamService;
import com.autospec.service.AgentEventService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentEventServiceImpl extends ServiceImpl<AgentEventMapper, AgentEvent>
        implements AgentEventService {

    private final AgentEventStreamService streamService;

    public AgentEventServiceImpl(AgentEventStreamService streamService) {
        this.streamService = streamService;
    }

    @Override
    public AgentEvent record(Long projectId, Long taskId, String eventType, String nodeName, String message, String payload) {
        AgentEvent event = new AgentEvent();
        event.setProjectId(projectId);
        event.setTaskId(taskId);
        event.setEventType(eventType);
        event.setNodeName(nodeName);
        event.setMessage(message);
        event.setPayload(payload);
        save(event);
        streamService.publish(event);
        return event;
    }

    @Override
    public List<AgentEvent> listByProjectId(Long projectId, int limit, int offset) {
        return lambdaQuery()
                .eq(AgentEvent::getProjectId, projectId)
                .orderByAsc(AgentEvent::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }
}
