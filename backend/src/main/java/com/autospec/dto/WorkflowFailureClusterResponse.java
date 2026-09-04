package com.autospec.dto;

import java.util.List;

public record WorkflowFailureClusterResponse(
        String dimension,
        String key,
        int count,
        List<Long> nodeRunIds
) {
}
