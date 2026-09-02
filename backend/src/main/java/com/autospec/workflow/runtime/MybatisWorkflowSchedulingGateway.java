package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class MybatisWorkflowSchedulingGateway implements WorkflowSchedulingGateway {
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowOutboxMapper outboxMapper;
    private final WorkflowRunMapper runMapper;
    private final ObjectMapper objectMapper;

    public MybatisWorkflowSchedulingGateway(
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowOutboxMapper outboxMapper,
            WorkflowRunMapper runMapper,
            ObjectMapper objectMapper
    ) {
        this.nodeRunMapper = nodeRunMapper;
        this.outboxMapper = outboxMapper;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<WorkflowNodeRun> listNodeRuns(long workflowRunId) {
        return nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, workflowRunId)
                .orderByAsc(WorkflowNodeRun::getNodeId)
                .orderByDesc(WorkflowNodeRun::getRevision)
                .orderByDesc(WorkflowNodeRun::getAttempt));
    }

    @Override
    @Transactional
    public boolean reserveAndAppendCommand(WorkflowNodeRun nodeRun, QueuedNodeCommand command) {
        LocalDateTime now = LocalDateTime.now();
        WorkflowBudgetReservation reservation = command.budgetReservation();
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.PENDING.name())
                .eq(WorkflowNodeRun::getLockVersion, nodeRun.getLockVersion())
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.QUEUED.name())
                .set(WorkflowNodeRun::getExecutionId, command.executionId())
                .set(WorkflowNodeRun::getContractHash, command.contractHash())
                .set(reservation != null, WorkflowNodeRun::getBudgetReservationId,
                        reservation == null ? null : reservation.reservationId())
                .set(reservation != null, WorkflowNodeRun::getReservedInputTokens,
                        reservation == null ? 0 : reservation.inputTokens())
                .set(reservation != null, WorkflowNodeRun::getReservedOutputTokens,
                        reservation == null ? 0 : reservation.outputTokens())
                .set(reservation != null, WorkflowNodeRun::getReservedCost,
                        reservation == null ? java.math.BigDecimal.ZERO : reservation.estimatedCost())
                .set(reservation != null, WorkflowNodeRun::getReservedModelCalls,
                        reservation == null ? 0 : reservation.modelCalls())
                .set(reservation != null, WorkflowNodeRun::getBudgetStatus, "RESERVED")
                .set(WorkflowNodeRun::getQueuedAt, now)
                .set(WorkflowNodeRun::getLockVersion, nodeRun.getLockVersion() + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (updated == 0) {
            return false;
        }
        if (reservation != null && runMapper.reserveModelBudget(
                command.workflowRunId(),
                reservation.totalTokens(),
                reservation.estimatedCost(),
                reservation.modelCalls()
        ) == 0) {
            rejectBudgetAdmission(nodeRun, command, now);
            return false;
        }

        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setEventId(command.eventId());
        outbox.setAggregateId(Long.toString(command.workflowRunId()));
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson(serialize(command));
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
        return true;
    }

    private void rejectBudgetAdmission(
            WorkflowNodeRun nodeRun,
            QueuedNodeCommand command,
            LocalDateTime now
    ) {
        String message = "Workflow budget could not be reserved before external invocation";
        nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getExecutionId, command.executionId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.QUEUED.name())
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.FAILED.name())
                .set(WorkflowNodeRun::getErrorCode, "BUDGET_PREAUTH_FAILED")
                .set(WorkflowNodeRun::getErrorMessage, message)
                .set(WorkflowNodeRun::getBudgetStatus, "REJECTED")
                .set(WorkflowNodeRun::getReservedInputTokens, 0)
                .set(WorkflowNodeRun::getReservedOutputTokens, 0)
                .set(WorkflowNodeRun::getReservedCost, java.math.BigDecimal.ZERO)
                .set(WorkflowNodeRun::getReservedModelCalls, 0)
                .set(WorkflowNodeRun::getFinishedAt, now)
                .set(WorkflowNodeRun::getUpdatedAt, now));
    }

    @Override
    @Transactional
    public boolean markSkipped(WorkflowNodeRun nodeRun, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.PENDING.name())
                .eq(WorkflowNodeRun::getLockVersion, nodeRun.getLockVersion())
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.SKIPPED.name())
                .set(WorkflowNodeRun::getErrorCode, "CONDITION_NOT_MATCHED")
                .set(WorkflowNodeRun::getErrorMessage, reason)
                .set(WorkflowNodeRun::getFinishedAt, now)
                .set(WorkflowNodeRun::getLockVersion, nodeRun.getLockVersion() + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        return updated == 1;
    }

    private String serialize(QueuedNodeCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize workflow command", exception);
        }
    }
}
