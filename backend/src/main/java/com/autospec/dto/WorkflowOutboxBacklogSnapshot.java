package com.autospec.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkflowOutboxBacklogSnapshot {
    private Long pendingCount;
    private LocalDateTime oldestCreatedAt;
}
