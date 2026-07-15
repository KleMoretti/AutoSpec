package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
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
    private final ObjectMapper objectMapper;

    public MybatisWorkflowSchedulingGateway(
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowOutboxMapper outboxMapper,
            ObjectMapper objectMapper
    ) {
        this.nodeRunMapper = nodeRunMapper;
        this.outboxMapper = outboxMapper;
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
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.PENDING.name())
                .eq(WorkflowNodeRun::getLockVersion, nodeRun.getLockVersion())
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.QUEUED.name())
                .set(WorkflowNodeRun::getExecutionId, command.executionId())
                .set(WorkflowNodeRun::getQueuedAt, now)
                .set(WorkflowNodeRun::getLockVersion, nodeRun.getLockVersion() + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (updated == 0) {
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

    private String serialize(QueuedNodeCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize workflow command", exception);
        }
    }
}
