package com.autospec.observability;

import com.autospec.entity.ModelInvocation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ModelInvocationMetricsTest {

    @Test
    void recordsOutcomeLatencyTokensAndCostWithBoundedLabels() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ModelInvocationMetrics metrics = new ModelInvocationMetrics(registry);
        ModelInvocation invocation = new ModelInvocation();
        invocation.setProviderKey("openai");
        invocation.setModelName("reasoning-model");
        invocation.setStatus("SUCCEEDED");
        invocation.setDurationMs(1250);
        invocation.setInputTokens(100);
        invocation.setOutputTokens(25);
        invocation.setEstimatedCost(new BigDecimal("0.0123"));

        metrics.record(invocation);

        assertThat(registry.get(ModelInvocationMetrics.INVOCATIONS)
                .tags("provider", "openai", "model", "reasoning-model", "status", "success")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get(ModelInvocationMetrics.DURATION)
                .tags("provider", "openai", "model", "reasoning-model", "status", "success")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1250);
        assertThat(registry.get(ModelInvocationMetrics.TOKENS)
                .tags("provider", "openai", "model", "reasoning-model", "token_type", "input")
                .counter()
                .count()).isEqualTo(100);
        assertThat(registry.get(ModelInvocationMetrics.TOKENS)
                .tags("provider", "openai", "model", "reasoning-model", "token_type", "output")
                .counter()
                .count()).isEqualTo(25);
        assertThat(registry.get(ModelInvocationMetrics.COST)
                .tags("provider", "openai", "model", "reasoning-model")
                .counter()
                .count()).isEqualTo(0.0123);
        assertThat(registry.scrape())
                .contains("autospec_model_invocations_total")
                .contains("autospec_model_invocation_duration_seconds_bucket")
                .contains("autospec_model_tokens_total")
                .contains("autospec_model_cost_total");
    }

    @Test
    void normalizesMissingLabelsAndNegativeMeasurements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ModelInvocationMetrics metrics = new ModelInvocationMetrics(registry);
        ModelInvocation invocation = new ModelInvocation();
        invocation.setStatus("FAILED");
        invocation.setDurationMs(-1);
        invocation.setInputTokens(-10);
        invocation.setOutputTokens(null);
        invocation.setEstimatedCost(new BigDecimal("-1"));

        metrics.record(invocation);

        assertThat(registry.get(ModelInvocationMetrics.INVOCATIONS)
                .tags("provider", "unknown", "model", "unknown", "status", "failed")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get(ModelInvocationMetrics.DURATION)
                .tags("provider", "unknown", "model", "unknown", "status", "failed")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS)).isZero();
        assertThat(registry.get(ModelInvocationMetrics.TOKENS)
                .tags("provider", "unknown", "model", "unknown", "token_type", "input")
                .counter()
                .count()).isZero();
        assertThat(registry.get(ModelInvocationMetrics.COST)
                .tags("provider", "unknown", "model", "unknown")
                .counter()
                .count()).isZero();
    }
}
