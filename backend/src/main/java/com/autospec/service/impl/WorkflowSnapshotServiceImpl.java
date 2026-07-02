package com.autospec.service.impl;

import com.autospec.dto.WorkflowSnapshotResponse;
import com.autospec.entity.WorkflowSnapshot;
import com.autospec.mapper.WorkflowSnapshotMapper;
import com.autospec.service.WorkflowSnapshotService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class WorkflowSnapshotServiceImpl extends ServiceImpl<WorkflowSnapshotMapper, WorkflowSnapshot> implements WorkflowSnapshotService {

    private final ObjectMapper objectMapper;

    public WorkflowSnapshotServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void ensureDefaultSnapshot(Long projectId) {
        boolean exists = lambdaQuery()
                .eq(WorkflowSnapshot::getProjectId, projectId)
                .eq(WorkflowSnapshot::getWorkflowKey, "autospec-v3")
                .exists();
        if (exists) {
            return;
        }
        WorkflowSnapshot snapshot = new WorkflowSnapshot();
        snapshot.setProjectId(projectId);
        snapshot.setWorkflowKey("autospec-v3");
        snapshot.setVersion("v3");
        snapshot.setGraphJson(defaultGraphJson());
        save(snapshot);
    }

    @Override
    public WorkflowSnapshotResponse latestResponse(Long projectId) {
        WorkflowSnapshot snapshot = lambdaQuery()
                .eq(WorkflowSnapshot::getProjectId, projectId)
                .orderByDesc(WorkflowSnapshot::getId)
                .last("limit 1")
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow snapshot not found"));
        try {
            Map<String, Object> graph = objectMapper.readValue(snapshot.getGraphJson(), new TypeReference<>() {
            });
            List<Map<String, Object>> nodes = castList(graph.get("nodes"));
            List<Map<String, Object>> edges = castList(graph.get("edges"));
            return new WorkflowSnapshotResponse(snapshot.getWorkflowKey(), snapshot.getVersion(), nodes, edges);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid workflow snapshot JSON", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private String defaultGraphJson() {
        return """
                {
                  "nodes": [
                    {"id": "product_manager", "label": "Product Manager", "artifactType": "PRD"},
                    {"id": "architect", "label": "Architect", "artifactType": "ARCHITECTURE_DESIGN"},
                    {"id": "backend_engineer", "label": "Backend Engineer", "artifactType": "BACKEND_DESIGN"},
                    {"id": "frontend_engineer", "label": "Frontend Engineer", "artifactType": "FRONTEND_SKELETON"},
                    {"id": "reviewer", "label": "Reviewer", "artifactType": "REVIEW_REPORT"}
                  ],
                  "edges": [
                    {"from": "product_manager", "to": "architect"},
                    {"from": "product_manager", "to": "backend_engineer"},
                    {"from": "architect", "to": "frontend_engineer"},
                    {"from": "backend_engineer", "to": "frontend_engineer"},
                    {"from": "frontend_engineer", "to": "reviewer"}
                  ]
                }
                """;
    }
}
