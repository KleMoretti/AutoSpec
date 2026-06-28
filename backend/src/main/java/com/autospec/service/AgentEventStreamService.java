package com.autospec.service;

import com.autospec.dto.AgentEventResponse;
import com.autospec.entity.AgentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AgentEventStreamService {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long projectId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(projectId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(projectId, emitter));
        emitter.onTimeout(() -> remove(projectId, emitter));
        emitter.onError(ignored -> remove(projectId, emitter));
        return emitter;
    }

    public void publish(AgentEvent event) {
        List<SseEmitter> projectEmitters = emitters.get(event.getProjectId());
        if (projectEmitters == null || projectEmitters.isEmpty()) {
            return;
        }
        AgentEventResponse response = AgentEventResponse.from(event);
        for (SseEmitter emitter : projectEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getEventType())
                        .data(response));
            } catch (IOException ex) {
                remove(event.getProjectId(), emitter);
            }
        }
    }

    private void remove(Long projectId, SseEmitter emitter) {
        List<SseEmitter> projectEmitters = emitters.get(projectId);
        if (projectEmitters != null) {
            projectEmitters.remove(emitter);
        }
    }
}
