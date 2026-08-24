package com.autospec.workflow.transport;

import com.autospec.entity.WorkflowEventDeadLetter;
import com.autospec.mapper.WorkflowEventDeadLetterMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MybatisWorkflowEventDeadLetterSink implements WorkflowEventDeadLetterSink {
    private final WorkflowEventDeadLetterMapper mapper;

    public MybatisWorkflowEventDeadLetterSink(WorkflowEventDeadLetterMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void quarantine(
            WorkflowStreamEventMessage message,
            InvalidWorkflowEventException failure
    ) {
        WorkflowEventDeadLetter value = new WorkflowEventDeadLetter();
        value.setStreamMessageId(message.messageId());
        value.setPayloadJson(message.payloadJson() == null ? "" : message.payloadJson());
        value.setErrorType(failure.getClass().getSimpleName());
        value.setErrorMessage(limit(failure.getMessage(), 2000));
        value.setStatus("OPEN");
        value.setCreatedAt(LocalDateTime.now());
        mapper.insertIfAbsent(value);
    }

    private String limit(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum);
    }
}
