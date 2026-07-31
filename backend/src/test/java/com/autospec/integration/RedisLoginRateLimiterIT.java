package com.autospec.integration;

import com.autospec.controller.GlobalExceptionHandler;
import com.autospec.dto.ApiErrorResponse;
import com.autospec.service.LoginRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

class RedisLoginRateLimiterIT extends RedisIntegrationTestSupport {

    @Test
    void atomicallyLimitsAttemptsAndAllowsLoginAfterReset() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LoginRateLimiter limiter = new LoginRateLimiter(
                redisTemplate,
                meterRegistry,
                true,
                2,
                Duration.ofSeconds(30)
        );

        assertThatCode(() -> limiter.checkAndRecord("Owner", "127.0.0.1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAndRecord("owner", "127.0.0.1"))
                .doesNotThrowAnyException();
        Throwable thrown = catchThrowable(() -> limiter.checkAndRecord("owner", "127.0.0.1"));
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException exception = (ResponseStatusException) thrown;
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exception.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/auth/login");
        ResponseEntity<ApiErrorResponse> response = new GlobalExceptionHandler()
                .handleResponseStatus(exception, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RATE_LIMITED");
        assertThat(meterRegistry.get("autospec.auth.login.rejections")
                .tag("reason", "rate_limit")
                .counter()
                .count()).isEqualTo(1);
        assertThat(redisTemplate.keys("autospec:auth:login:*")).singleElement()
                .asString()
                .doesNotContainIgnoringCase("owner");

        limiter.reset("owner", "127.0.0.1");

        assertThatCode(() -> limiter.checkAndRecord("owner", "127.0.0.1"))
                .doesNotThrowAnyException();
    }
}
