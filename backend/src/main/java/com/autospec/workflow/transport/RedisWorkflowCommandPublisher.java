package com.autospec.workflow.transport;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RedisWorkflowCommandPublisher implements WorkflowCommandPublisher {
    private final StringRedisTemplate redisTemplate;

    public RedisWorkflowCommandPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publish(String stream, String eventId, String payloadJson) {
        MapRecord<String, String, String> record = StreamRecords
                .newRecord()
                .in(stream)
                .ofMap(Map.of("event_id", eventId, "payload", payloadJson));
        redisTemplate.opsForStream().add(record);
    }
}
