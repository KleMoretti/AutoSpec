package com.autospec;

import com.autospec.dto.PaginationRequest;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.service.WorkflowDeadLetterService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDeadLetterServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(
                        new MybatisConfiguration(),
                        WorkflowDeadLetterServiceTest.class.getName()
                ),
                WorkflowOutbox.class
        );
    }

    @Test
    void listsOnlyRequestedRunAndDeadLetterStatus() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowOutbox expected = deadLetter();
        when(mapper.selectList(any())).thenReturn(List.of(expected));
        WorkflowDeadLetterService service = new WorkflowDeadLetterService(mapper);

        List<WorkflowOutbox> result = service.listByWorkflowRunId(
                7L,
                "dead_letter",
                new PaginationRequest(25, 10)
        );

        assertThat(result).containsExactly(expected);
        ArgumentCaptor<Wrapper<WorkflowOutbox>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(query.capture());
        LambdaQueryWrapper<WorkflowOutbox> wrapper =
                (LambdaQueryWrapper<WorkflowOutbox>) query.getValue();
        assertThat(wrapper.getCustomSqlSegment())
                .contains("aggregate_id")
                .contains("status")
                .contains("ORDER BY id DESC")
                .contains("limit 25 offset 10");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("7", "DEAD_LETTER");
    }

    @Test
    void rejectsUnsupportedQueryStatusBeforeCallingDatabase() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowDeadLetterService service = new WorkflowDeadLetterService(mapper);

        assertThatThrownBy(() -> service.listByWorkflowRunId(
                7L,
                "PENDING",
                new PaginationRequest(25, 0)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void replayUsesCompareAndSetAndPreservesEventIdentity() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowOutbox outbox = deadLetter();
        when(mapper.selectOne(any())).thenReturn(outbox);
        when(mapper.update(isNull(), any())).thenReturn(1);
        WorkflowDeadLetterService service = new WorkflowDeadLetterService(mapper);

        WorkflowOutbox replayed = service.replay(7L, 21L);

        assertThat(replayed.getEventId()).isEqualTo("command-1");
        assertThat(replayed.getStatus()).isEqualTo("PENDING");
        assertThat(replayed.getRetryCount()).isZero();
        ArgumentCaptor<Wrapper<WorkflowOutbox>> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), update.capture());
        LambdaUpdateWrapper<WorkflowOutbox> wrapper =
                (LambdaUpdateWrapper<WorkflowOutbox>) update.getValue();
        assertThat(wrapper.getSqlSet())
                .contains("status")
                .contains("retry_count")
                .contains("next_retry_at")
                .contains("closed_at");
        assertThat(wrapper.getCustomSqlSegment())
                .contains("id")
                .contains("aggregate_id")
                .contains("status");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("7", "DEAD_LETTER", "PENDING", 0);
    }

    @Test
    void closeUsesCompareAndSetAndRecordsClosureTime() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowOutbox outbox = deadLetter();
        when(mapper.selectOne(any())).thenReturn(outbox);
        when(mapper.update(isNull(), any())).thenReturn(1);
        WorkflowDeadLetterService service = new WorkflowDeadLetterService(mapper);

        WorkflowOutbox closed = service.close(7L, 21L);

        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getClosedAt()).isNotNull();
        ArgumentCaptor<Wrapper<WorkflowOutbox>> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), update.capture());
        LambdaUpdateWrapper<WorkflowOutbox> wrapper =
                (LambdaUpdateWrapper<WorkflowOutbox>) update.getValue();
        assertThat(wrapper.getCustomSqlSegment())
                .contains("id")
                .contains("aggregate_id")
                .contains("status");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("7", "DEAD_LETTER", "CLOSED");
    }

    @Test
    void rejectsReplayAfterDeadLetterWasAlreadyClosed() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowOutbox outbox = deadLetter();
        outbox.setStatus("CLOSED");
        when(mapper.selectOne(any())).thenReturn(outbox);
        WorkflowDeadLetterService service = new WorkflowDeadLetterService(mapper);

        assertThatThrownBy(() -> service.replay(7L, 21L))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(mapper, never()).update(isNull(), any());
    }

    private WorkflowOutbox deadLetter() {
        LocalDateTime now = LocalDateTime.now();
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setId(21L);
        outbox.setEventId("command-1");
        outbox.setAggregateId("7");
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson("{}");
        outbox.setStatus("DEAD_LETTER");
        outbox.setRetryCount(5);
        outbox.setLastErrorType("IllegalStateException");
        outbox.setLastErrorAt(now);
        outbox.setDeadLetteredAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }
}
