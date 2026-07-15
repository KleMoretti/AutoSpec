package com.autospec.workflow.transport;

import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowOutboxPublisherTest {

    @Test
    void publishesPendingCommandThenMarksOutboxAsPublished() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowCommandPublisher commandPublisher = mock(WorkflowCommandPublisher.class);
        WorkflowOutbox outbox = pendingOutbox();
        when(mapper.selectList(any())).thenReturn(List.of(outbox));
        when(mapper.update(any(), any())).thenReturn(1);
        WorkflowOutboxPublisher publisher = new WorkflowOutboxPublisher(mapper, commandPublisher);

        int published = publisher.publishPending(10);

        assertThat(published).isEqualTo(1);
        verify(commandPublisher).publish(
                "autospec.workflow.commands", outbox.getEventId(), outbox.getPayloadJson()
        );
        verify(mapper).update(any(), any());
    }

    @Test
    void leavesOutboxPendingWhenRedisPublicationFails() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowCommandPublisher commandPublisher = mock(WorkflowCommandPublisher.class);
        WorkflowOutbox outbox = pendingOutbox();
        when(mapper.selectList(any())).thenReturn(List.of(outbox));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(commandPublisher)
                .publish(any(), any(), any());
        WorkflowOutboxPublisher publisher = new WorkflowOutboxPublisher(mapper, commandPublisher);

        assertThatThrownBy(() -> publisher.publishPending(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis unavailable");

        verify(mapper, never()).update(any(), any());
    }

    private WorkflowOutbox pendingOutbox() {
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setId(21L);
        outbox.setEventId("command-1");
        outbox.setAggregateId("7");
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson("{\"execution_id\":\"7:fixture:1:1\"}");
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(LocalDateTime.now());
        return outbox;
    }
}
