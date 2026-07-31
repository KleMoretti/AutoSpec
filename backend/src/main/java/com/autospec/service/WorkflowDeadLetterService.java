package com.autospec.service;

import com.autospec.dto.PaginationRequest;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class WorkflowDeadLetterService {
    static final String DEAD_LETTER = "DEAD_LETTER";
    static final String CLOSED = "CLOSED";
    private static final Set<String> QUERYABLE_STATUSES = Set.of(DEAD_LETTER, CLOSED);

    private final WorkflowOutboxMapper outboxMapper;

    public WorkflowDeadLetterService(WorkflowOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    public List<WorkflowOutbox> listByWorkflowRunId(
            long workflowRunId,
            String status,
            PaginationRequest pagination
    ) {
        String normalizedStatus = normalizeQueryableStatus(status);
        return outboxMapper.selectList(new LambdaQueryWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getAggregateId, Long.toString(workflowRunId))
                .eq(WorkflowOutbox::getStatus, normalizedStatus)
                .orderByDesc(WorkflowOutbox::getId)
                .last("limit " + pagination.limit() + " offset " + pagination.offset()));
    }

    @Transactional
    public WorkflowOutbox replay(long workflowRunId, long outboxId) {
        WorkflowOutbox outbox = requireForRun(workflowRunId, outboxId);
        requireStatus(outbox, DEAD_LETTER, "Only open dead letters can be replayed");
        LocalDateTime now = LocalDateTime.now();
        int updated = outboxMapper.update(null, new LambdaUpdateWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getId, outboxId)
                .eq(WorkflowOutbox::getAggregateId, Long.toString(workflowRunId))
                .eq(WorkflowOutbox::getStatus, DEAD_LETTER)
                .set(WorkflowOutbox::getStatus, "PENDING")
                .set(WorkflowOutbox::getRetryCount, 0)
                .set(WorkflowOutbox::getNextRetryAt, null)
                .set(WorkflowOutbox::getClosedAt, null)
                .set(WorkflowOutbox::getUpdatedAt, now));
        if (updated == 0) {
            throw conflict("Dead letter state changed before replay");
        }
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(null);
        outbox.setClosedAt(null);
        outbox.setUpdatedAt(now);
        return outbox;
    }

    @Transactional
    public WorkflowOutbox close(long workflowRunId, long outboxId) {
        WorkflowOutbox outbox = requireForRun(workflowRunId, outboxId);
        requireStatus(outbox, DEAD_LETTER, "Only open dead letters can be closed");
        LocalDateTime now = LocalDateTime.now();
        int updated = outboxMapper.update(null, new LambdaUpdateWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getId, outboxId)
                .eq(WorkflowOutbox::getAggregateId, Long.toString(workflowRunId))
                .eq(WorkflowOutbox::getStatus, DEAD_LETTER)
                .set(WorkflowOutbox::getStatus, CLOSED)
                .set(WorkflowOutbox::getNextRetryAt, null)
                .set(WorkflowOutbox::getClosedAt, now)
                .set(WorkflowOutbox::getUpdatedAt, now));
        if (updated == 0) {
            throw conflict("Dead letter state changed before close");
        }
        outbox.setStatus(CLOSED);
        outbox.setNextRetryAt(null);
        outbox.setClosedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }

    private WorkflowOutbox requireForRun(long workflowRunId, long outboxId) {
        WorkflowOutbox outbox = outboxMapper.selectOne(new LambdaQueryWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getId, outboxId)
                .eq(WorkflowOutbox::getAggregateId, Long.toString(workflowRunId)));
        if (outbox == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow dead letter not found");
        }
        return outbox;
    }

    private String normalizeQueryableStatus(String status) {
        String normalized = status == null ? DEAD_LETTER : status.trim().toUpperCase(Locale.ROOT);
        if (!QUERYABLE_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "status must be DEAD_LETTER or CLOSED"
            );
        }
        return normalized;
    }

    private void requireStatus(WorkflowOutbox outbox, String expected, String message) {
        if (!expected.equals(outbox.getStatus())) {
            throw conflict(message);
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
