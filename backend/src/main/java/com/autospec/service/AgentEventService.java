package com.autospec.service;

import com.autospec.entity.AgentEvent;
import com.baomidou.mybatisplus.extension.service.IService;

public interface AgentEventService extends IService<AgentEvent> {

    AgentEvent record(Long projectId, Long taskId, String eventType, String nodeName, String message, String payload);
}
