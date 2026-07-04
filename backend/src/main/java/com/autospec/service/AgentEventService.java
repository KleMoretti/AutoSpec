package com.autospec.service;

import com.autospec.entity.AgentEvent;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface AgentEventService extends IService<AgentEvent> {

    AgentEvent record(Long projectId, Long taskId, String eventType, String nodeName, String message, String payload);

    List<AgentEvent> listByProjectId(Long projectId, int limit, int offset);
}
