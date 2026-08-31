package com.autospec.workflow.transport;

import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
        WorkflowOutboxPublisher publisher = publisher(mapper, commandPublisher);

        int published = publisher.publishPending(10);

        assertThat(published).isEqualTo(1);
        verify(commandPublisher).publish(
                "autospec.workflow.commands", outbox.getEventId(), outbox.getPayloadJson()
        );
        verify(mapper).update(any(), any());
    }

    @Test
    void handlesArtifactApprovalLocallyThenMarksOutboxAsPublished() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowCommandPublisher commandPublisher = mock(WorkflowCommandPublisher.class);
        ArtifactApprovalOutboxHandler handler = mock(ArtifactApprovalOutboxHandler.class);
        WorkflowOutbox outbox = pendingOutbox();
        outbox.setEventType("ARTIFACT_APPROVED");
        outbox.setAggregateId("artifact:9");
        when(mapper.selectList(any())).thenReturn(List.of(outbox));
        when(mapper.update(any(), any())).thenReturn(1);
        WorkflowOutboxPublisher publisher = new WorkflowOutboxPublisher(
                mapper,
                commandPublisher,
                retryPolicy(),
                WorkflowTransportMetrics.isolated(),
                handler
        );

        assertThat(publisher.publishPending(10)).isEqualTo(1);

        verify(handler).handle(outbox);
        verifyNoInteractions(commandPublisher);
        verify(mapper).update(any(), any());
    }

    @Test
    void schedulesFailedPublicationAndContinuesWithRemainingCommands() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowCommandPublisher commandPublisher = mock(WorkflowCommandPublisher.class);
        WorkflowOutbox failed = pendingOutbox();
        WorkflowOutbox successful = pendingOutbox();
        successful.setId(22L);
        successful.setEventId("command-2");
        when(mapper.selectList(any())).thenReturn(List.of(failed, successful));
        when(mapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .doNothing()
                .when(commandPublisher)
                .publish(any(), any(), any());
        WorkflowOutboxPublisher publisher = publisher(mapper, commandPublisher);

        int published = publisher.publishPending(10);

        assertThat(published).isEqualTo(1);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<WorkflowOutbox>> updates =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(2)).update(
                org.mockito.ArgumentMatchers.isNull(),
                updates.capture()
        );
        UpdateWrapper<WorkflowOutbox> retryUpdate =
                (UpdateWrapper<WorkflowOutbox>) updates.getAllValues().get(0);
        UpdateWrapper<WorkflowOutbox> publishedUpdate =
                (UpdateWrapper<WorkflowOutbox>) updates.getAllValues().get(1);
        assertThat(retryUpdate.getSqlSet())
                .contains("retry_count = retry_count + 1")
                .contains("next_retry_at")
                .contains("last_error_type")
                .contains("last_error_at");
        assertThat(publishedUpdate.getSqlSet())
                .contains("status")
                .contains("published_at");
    }

    @Test
    void movesCommandToDeadLetterAfterMaximumAttempts() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowCommandPublisher commandPublisher = mock(WorkflowCommandPublisher.class);
        WorkflowOutbox outbox = pendingOutbox();
        outbox.setRetryCount(4);
        when(mapper.selectList(any())).thenReturn(List.of(outbox));
        when(mapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(commandPublisher)
                .publish(any(), any(), any());
        WorkflowOutboxPublisher publisher = publisher(mapper, commandPublisher);

        assertThat(publisher.publishPending(10)).isZero();

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<WorkflowOutbox>> update =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        UpdateWrapper<WorkflowOutbox> deadLetterUpdate =
                (UpdateWrapper<WorkflowOutbox>) update.getValue();
        assertThat(deadLetterUpdate.getSqlSet())
                .contains("retry_count = retry_count + 1")
                .contains("next_retry_at")
                .contains("dead_lettered_at")
                .contains("last_error_type");
        assertThat(deadLetterUpdate.getParamNameValuePairs().values())
                .contains("DEAD_LETTER", "IllegalStateException");
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

    private WorkflowOutboxPublisher publisher(
            WorkflowOutboxMapper mapper,
            WorkflowCommandPublisher commandPublisher
    ) {
        return new WorkflowOutboxPublisher(mapper, commandPublisher, retryPolicy());
    }

    private OutboxRetryPolicy retryPolicy() {
        return new OutboxRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                0.2,
                () -> 0.5
        );
    }
}
