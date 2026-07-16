package com.autospec.workflow.transport;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import org.springframework.data.redis.connection.DecoratedRedisConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class RedisWorkflowEventStreamClient implements WorkflowEventStreamClient {
    private static final String AUTO_CLAIM_COMMAND = "XAUTOCLAIM";

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
    public List<WorkflowStreamEventMessage> claimStale(
            String stream,
            String group,
            String consumer,
            Duration minimumIdleTime,
            int count
    ) {
        if (minimumIdleTime.isNegative()) {
            throw new IllegalArgumentException("minimumIdleTime must not be negative");
        }
        int claimCount = Math.max(1, Math.min(count, 100));
        byte[][] arguments = new byte[][]{
                raw(stream),
                raw(group),
                raw(consumer),
                raw(Long.toString(minimumIdleTime.toMillis())),
                raw("0-0"),
                raw("COUNT"),
                raw(Integer.toString(claimCount))
        };
        Object response = redisTemplate.execute(
                (RedisCallback<Object>) connection -> executeAutoClaim(connection, arguments),
                true
        );
        return parseClaimedMessages(response);
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

    private Object executeAutoClaim(RedisConnection connection, byte[][] arguments) {
        RedisConnection targetConnection = unwrap(connection);
        if (targetConnection instanceof LettuceConnection lettuceConnection) {
            return lettuceConnection.execute(
                    AUTO_CLAIM_COMMAND,
                    new NestedMultiOutput<>(ByteArrayCodec.INSTANCE),
                    arguments
            );
        }
        return targetConnection.commands().execute(AUTO_CLAIM_COMMAND, arguments);
    }

    private RedisConnection unwrap(RedisConnection connection) {
        RedisConnection current = connection;
        while (current instanceof DecoratedRedisConnection decorated
                && decorated.getDelegate() != current) {
            current = decorated.getDelegate();
        }
        return current;
    }

    private List<WorkflowStreamEventMessage> parseClaimedMessages(Object response) {
        List<?> reply = asList(response);
        while (reply != null && reply.size() == 1) {
            List<?> nestedReply = asList(reply.get(0));
            if (nestedReply == null) {
                break;
            }
            reply = nestedReply;
        }
        if (reply == null || reply.size() < 2) {
            return List.of();
        }

        List<?> claimedEntries = asList(reply.get(1));
        if (claimedEntries == null || claimedEntries.isEmpty()) {
            return List.of();
        }

        List<WorkflowStreamEventMessage> messages = new ArrayList<>(claimedEntries.size());
        for (Object claimedEntry : claimedEntries) {
            WorkflowStreamEventMessage message = parseClaimedEntry(claimedEntry);
            if (message != null) {
                messages.add(message);
            }
        }
        return List.copyOf(messages);
    }

    private WorkflowStreamEventMessage parseClaimedEntry(Object claimedEntry) {
        List<?> entry = asList(claimedEntry);
        if (entry == null || entry.size() < 2) {
            return null;
        }

        String messageId = decode(entry.get(0));
        String payload = extractPayload(entry.get(1));
        if (messageId == null || messageId.isBlank() || payload == null) {
            return null;
        }
        return new WorkflowStreamEventMessage(messageId, payload);
    }

    private String extractPayload(Object rawFields) {
        if (rawFields instanceof Map<?, ?> fields) {
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                if ("payload".equals(decode(field.getKey()))) {
                    return decode(field.getValue());
                }
            }
            return null;
        }

        List<?> fields = asList(rawFields);
        if (fields == null) {
            return null;
        }
        for (int index = 0; index + 1 < fields.size(); index += 2) {
            if ("payload".equals(decode(fields.get(index)))) {
                return decode(fields.get(index + 1));
            }
        }
        return null;
    }

    private List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof Object[] array) {
            return Arrays.asList(array);
        }
        return null;
    }

    private String decode(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.asReadOnlyBuffer();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value == null ? null : String.valueOf(value);
    }

    private byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
