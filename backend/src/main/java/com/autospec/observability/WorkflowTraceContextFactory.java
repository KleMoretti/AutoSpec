package com.autospec.observability;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class WorkflowTraceContextFactory {
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^(?!ff-)[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"
    );
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_SPAN_ID = "0".repeat(16);

    private final SecureRandom secureRandom;

    public WorkflowTraceContextFactory() {
        this(new SecureRandom());
    }

    WorkflowTraceContextFactory(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public Context create(String correlationId) {
        String resolvedCorrelationId = correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId.strip();
        return new Context(
                resolvedCorrelationId,
                "00-" + traceId(resolvedCorrelationId) + "-" + spanId() + "-01",
                null
        );
    }

    public static boolean isValidTraceparent(String value) {
        if (value == null || !TRACEPARENT.matcher(value).matches()) {
            return false;
        }
        String[] parts = value.split("-");
        return !ZERO_TRACE_ID.equals(parts[1]) && !ZERO_SPAN_ID.equals(parts[2]);
    }

    public static String extractTraceId(String traceparent) {
        return isValidTraceparent(traceparent) ? traceparent.split("-")[1] : null;
    }

    public static String extractSpanId(String traceparent) {
        return isValidTraceparent(traceparent) ? traceparent.split("-")[2] : null;
    }

    private String traceId(String correlationId) {
        String normalized = correlationId.replace("-", "").toLowerCase(Locale.ROOT);
        if (normalized.matches("[0-9a-f]{32}") && !ZERO_TRACE_ID.equals(normalized)) {
            return normalized;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(correlationId.getBytes(StandardCharsets.UTF_8));
            String traceId = HexFormat.of().formatHex(digest, 0, 16);
            return ZERO_TRACE_ID.equals(traceId)
                    ? HexFormat.of().formatHex(randomBytes(16))
                    : traceId;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String spanId() {
        String spanId;
        do {
            spanId = HexFormat.of().formatHex(randomBytes(8));
        } while (ZERO_SPAN_ID.equals(spanId));
        return spanId;
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    public record Context(String correlationId, String traceparent, String tracestate) {
    }
}
