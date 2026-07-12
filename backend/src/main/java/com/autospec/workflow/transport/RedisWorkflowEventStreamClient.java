package com.autospec.workflow.transport;

import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

public class RedisWorkflowEventStreamClient implements WorkflowEventStreamClient {
    private final StringRedisTemplate redisTemplate;
    private final Duration blockDuration;

    public RedisWorkflowEventStreamClient(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Duration.ofSeconds(2));
    }

    public RedisWorkflowEventStreamClient(
            StringRedisTemplate redisTemplate,
            Duration blockDuration
    ) {
        this.redisTemplate = redisTemplate;
        this.blockDuration = blockDuration;
    }

    @Override
    public void ensureGroup(String stream, String group) {
        try {
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
        } catch (RuntimeException exception) {
            if (!containsBusyGroup(exception)) {
                throw exception;
            }
        }
    }

    @Override
    public List<WorkflowStreamEventMessage> read(
            String stream,
            String group,
            String consumer,
            int count
    ) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(count).block(blockDuration),
                StreamOffset.create(stream, ReadOffset.lastConsumed())
        );
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(record -> new WorkflowStreamEventMessage(
                        record.getId().getValue(),
                        String.valueOf(record.getValue().get("payload"))
                ))
                .toList();
    }

    @Override
    public void acknowledge(String stream, String group, String messageId) {
        redisTemplate.opsForStream().acknowledge(stream, group, RecordId.of(messageId));
    }

    private boolean containsBusyGroup(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
