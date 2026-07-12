package com.autospec.workflow.transport;

import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkflowOutboxPublisher {
    public static final String COMMAND_STREAM = "autospec.workflow.commands";

    private final WorkflowOutboxMapper outboxMapper;
    private final WorkflowCommandPublisher commandPublisher;

    public WorkflowOutboxPublisher(
            WorkflowOutboxMapper outboxMapper,
            WorkflowCommandPublisher commandPublisher
    ) {
        this.outboxMapper = outboxMapper;
        this.commandPublisher = commandPublisher;
    }

    public int publishPending(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowOutbox> pending = outboxMapper.selectList(
                new LambdaQueryWrapper<WorkflowOutbox>()
                        .eq(WorkflowOutbox::getStatus, "PENDING")
                        .and(wrapper -> wrapper
                                .isNull(WorkflowOutbox::getNextRetryAt)
                                .or()
                                .le(WorkflowOutbox::getNextRetryAt, now))
                        .orderByAsc(WorkflowOutbox::getId)
                        .last("limit " + safeLimit)
        );
        int published = 0;
        for (WorkflowOutbox outbox : pending) {
            commandPublisher.publish(COMMAND_STREAM, outbox.getEventId(), outbox.getPayloadJson());
            int updated = outboxMapper.update(null, new UpdateWrapper<WorkflowOutbox>()
                    .eq("id", outbox.getId())
                    .eq("status", "PENDING")
                    .set("status", "PUBLISHED")
                    .set("published_at", LocalDateTime.now())
                    .set("updated_at", LocalDateTime.now()));
            published += updated;
        }
        return published;
    }
}
