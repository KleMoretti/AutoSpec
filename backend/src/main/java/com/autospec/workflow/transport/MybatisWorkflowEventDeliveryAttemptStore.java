package com.autospec.workflow.transport;

import com.autospec.mapper.WorkflowEventDeliveryAttemptMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MybatisWorkflowEventDeliveryAttemptStore implements WorkflowEventDeliveryAttemptStore {
    private final WorkflowEventDeliveryAttemptMapper mapper;

    public MybatisWorkflowEventDeliveryAttemptStore(WorkflowEventDeliveryAttemptMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int recordAttempt(String streamMessageId, String consumerGroup, LocalDateTime now) {
        mapper.recordAttempt(streamMessageId, consumerGroup, now);
        Integer count = mapper.selectDeliveryCount(streamMessageId, consumerGroup);
        return count == null ? 1 : count;
    }
}
