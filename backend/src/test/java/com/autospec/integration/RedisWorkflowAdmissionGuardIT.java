package com.autospec.integration;

import com.autospec.dto.WorkflowOutboxBacklogSnapshot;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.workflow.transport.WorkflowAdmissionGuard;
import com.autospec.workflow.transport.WorkflowOutboxPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisWorkflowAdmissionGuardIT extends RedisIntegrationTestSupport {
    private static final String COMMAND_STREAM = WorkflowOutboxPublisher.COMMAND_STREAM;
    private static final String WORKER_GROUP = "autospec-workers";

    @Test
    void countsBothUndeliveredAndPendingWorkerCommands() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowOutboxBacklogSnapshot emptyOutbox = new WorkflowOutboxBacklogSnapshot();
        emptyOutbox.setPendingCount(0L);
        when(mapper.selectPendingBacklog()).thenReturn(emptyOutbox);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowAdmissionGuard guard = new WorkflowAdmissionGuard(
                mapper,
                redisTemplate,
                registry,
                true,
                100,
                Duration.ofMinutes(5),
                3,
                Duration.ofSeconds(2)
        );

        redisTemplate.opsForStream().add(COMMAND_STREAM, Map.of("payload", "seed"));
        redisTemplate.opsForStream().createGroup(
                COMMAND_STREAM,
                ReadOffset.latest(),
                WORKER_GROUP
        );
        for (int index = 0; index < 3; index++) {
            redisTemplate.opsForStream().add(
                    COMMAND_STREAM,
                    Map.of("payload", "command-" + index)
            );
        }

        assertRejected(guard);

        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(WORKER_GROUP, "worker-1"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(COMMAND_STREAM, ReadOffset.lastConsumed())
        );
        assertThat(delivered).hasSize(3);
        assertRejected(guard);

        RecordId[] ids = delivered.stream().map(MapRecord::getId).toArray(RecordId[]::new);
        redisTemplate.opsForStream().acknowledge(COMMAND_STREAM, WORKER_GROUP, ids);

        assertThatCode(guard::admit).doesNotThrowAnyException();
        assertThat(registry.get(WorkflowAdmissionGuard.REJECTIONS)
                .tag("reason", "worker_backlog")
                .counter()
                .count()).isEqualTo(2);
    }

    private void assertRejected(WorkflowAdmissionGuard guard) {
        Throwable thrown = catchThrowable(guard::admit);
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException exception = (ResponseStatusException) thrown;
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exception.getReason()).contains("worker command backlog");
        assertThat(exception.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    }
}
