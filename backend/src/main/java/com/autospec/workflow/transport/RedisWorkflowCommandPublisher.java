package com.autospec.workflow.transport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RedisWorkflowCommandPublisher implements WorkflowCommandPublisher {
    static final long DEFAULT_STREAM_MAX_LENGTH = 1_000_000;

    private final StringRedisTemplate redisTemplate;
    private final long streamMaxLength;

    public RedisWorkflowCommandPublisher(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_STREAM_MAX_LENGTH);
    }

    @Autowired
    public RedisWorkflowCommandPublisher(
            StringRedisTemplate redisTemplate,
            @Value("${autospec.workflow.commands.stream-max-length:1000000}")
            long streamMaxLength
    ) {
        if (streamMaxLength < 1) {
            throw new IllegalArgumentException("streamMaxLength must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.streamMaxLength = streamMaxLength;
    }

    @Override
    public void publish(String stream, String eventId, String payloadJson) {
        MapRecord<String, String, String> record = StreamRecords
                .newRecord()
                .in(stream)
                .ofMap(Map.of("event_id", eventId, "payload", payloadJson));
        ByteRecord serialized = record.serialize(redisTemplate.getStringSerializer());
        RecordId recordId = redisTemplate.execute(
                (RedisCallback<RecordId>) connection -> connection.streamCommands().xAdd(
                        serialized,
                        RedisStreamCommands.XAddOptions.maxlen(streamMaxLength)
                                .approximateTrimming(true)
                ),
                true
        );
        if (recordId == null) {
            throw new IllegalStateException("Redis XADD did not return a record id");
        }
    }
}
