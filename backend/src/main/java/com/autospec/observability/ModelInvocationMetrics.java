package com.autospec.observability;

import com.autospec.entity.ModelInvocation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;

@Component
public class ModelInvocationMetrics {
    public static final String INVOCATIONS = "autospec.model.invocations";
    public static final String DURATION = "autospec.model.invocation.duration";
    public static final String TOKENS = "autospec.model.tokens";
    public static final String COST = "autospec.model.cost";

    private static final int MAX_TAG_LENGTH = 128;

    private final MeterRegistry registry;

    public ModelInvocationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(ModelInvocation invocation) {
        String provider = boundedTag(invocation.getProviderKey());
        String model = boundedTag(invocation.getModelName());
        String status = normalizedStatus(invocation.getStatus());
        Tags invocationTags = Tags.of(
                "provider", provider,
                "model", model,
                "status", status
        );

        Counter.builder(INVOCATIONS)
                .description("Persisted model invocations by outcome")
                .tags(invocationTags)
                .register(registry)
                .increment();

        Timer.builder(DURATION)
                .description("Persisted model invocation duration")
                .tags(invocationTags)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(registry)
                .record(Duration.ofMillis(nonNegativeDuration(invocation.getDurationMs())));

        recordTokens(provider, model, "input", invocation.getInputTokens());
        recordTokens(provider, model, "output", invocation.getOutputTokens());
        recordCost(provider, model, invocation.getEstimatedCost());
    }

    private void recordTokens(String provider, String model, String type, Integer value) {
        Counter.builder(TOKENS)
                .description("Model tokens persisted by token type")
                .tags("provider", provider, "model", model, "token_type", type)
                .register(registry)
                .increment(nonNegative(value));
    }

    private void recordCost(String provider, String model, BigDecimal value) {
        Counter.builder(COST)
                .description("Estimated model cost persisted by model")
                .tags("provider", provider, "model", model)
                .register(registry)
                .increment(nonNegative(value));
    }

    private String normalizedStatus(String value) {
        if (value == null) {
            return "other";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "SUCCEEDED" -> "success";
            case "FAILURE", "FAILED" -> "failed";
            default -> "other";
        };
    }

    private String boundedTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String stripped = value.strip();
        return stripped.length() <= MAX_TAG_LENGTH
                ? stripped
                : stripped.substring(0, MAX_TAG_LENGTH);
    }

    private double nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private long nonNegativeDuration(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private double nonNegative(BigDecimal value) {
        return value == null ? 0 : Math.max(0, value.doubleValue());
    }
}
