package com.autospec.dto;

import java.util.List;
import java.util.Map;

public record WorkflowSnapshotResponse(
        String workflowKey,
        String version,
        List<Map<String, Object>> nodes,
        List<Map<String, Object>> edges
) {
}
