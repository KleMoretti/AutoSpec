package com.autospec.integration;

import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.workflow.transport.WorkflowOutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "autospec.workflow.outbox.retry.base-delay=100ms",
        "autospec.workflow.outbox.retry.max-delay=100ms",
        "autospec.workflow.outbox.retry.jitter-ratio=0",
        "spring.data.redis.timeout=500ms",
        "spring.data.redis.connect-timeout=500ms"
})
@ActiveProfiles("integration-test")
@Testcontainers
class RedisOutboxRecoveryIT extends MySqlIntegrationTestSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisOutboxRecoveryIT.class);
    private static final int REDIS_PORT = 6379;
    private static final Duration RECOVERY_OBJECTIVE = Duration.ofSeconds(10);
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")
    )
            .withExposedPorts(REDIS_PORT)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0")
    ).withNetwork(NETWORK);

    private static ToxiproxyContainer.ContainerProxy redisProxy;

    @Autowired
    private WorkflowOutboxPublisher publisher;

    @Autowired
    private WorkflowOutboxMapper outboxMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> proxy().getContainerIpAddress());
        registry.add("spring.data.redis.port", () -> proxy().getProxyPort());
    }

    @BeforeEach
    void clearRedis() {
        proxy().setConnectionCut(false);
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @AfterEach
    void restoreRedisConnection() {
        proxy().setConnectionCut(false);
    }

    @AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    @Test
    void pendingOutboxPublishesAfterRedisNetworkRecoversWithoutDatabaseRepair()
            throws Exception {
        WorkflowOutbox outbox = persistPendingOutbox();
        Instant failureStartedAt = Instant.now();
        proxy().setConnectionCut(true);

        assertThat(publisher.publishPending(10)).isZero();

        long detectionMillis = Duration.between(failureStartedAt, Instant.now()).toMillis();
        WorkflowOutbox failedAttempt = outboxMapper.selectById(outbox.getId());
        assertThat(detectionMillis).isLessThan(RECOVERY_OBJECTIVE.toMillis());
        assertThat(failedAttempt.getStatus()).isEqualTo("PENDING");
        assertThat(failedAttempt.getRetryCount()).isEqualTo(1);
        assertThat(failedAttempt.getNextRetryAt()).isNotNull();
        assertThat(failedAttempt.getLastErrorType()).isNotBlank();
        assertThat(failedAttempt.getPublishedAt()).isNull();

        proxy().setConnectionCut(false);
        Instant restoredAt = Instant.now();
        waitUntilEligible(failedAttempt.getNextRetryAt());
        assertThat(publisher.publishPending(10)).isGreaterThanOrEqualTo(1);

        long recoveryMillis = Duration.between(restoredAt, Instant.now()).toMillis();
        WorkflowOutbox recovered = outboxMapper.selectById(outbox.getId());
        assertThat(recoveryMillis).isLessThan(RECOVERY_OBJECTIVE.toMillis());
        assertThat(recovered.getStatus()).isEqualTo("PUBLISHED");
        assertThat(recovered.getPublishedAt()).isNotNull();
        var publishedCommands = redisTemplate.opsForStream().range(
                WorkflowOutboxPublisher.COMMAND_STREAM,
                Range.unbounded()
        );
        var outboxCommands = publishedCommands.stream()
                .filter(record -> outbox.getEventId().equals(record.getValue().get("event_id")))
                .toList();
        assertThat(outboxCommands).hasSize(1);
        assertThat(outboxCommands).allSatisfy(record -> assertThat(record.getValue())
                .containsEntry("event_id", outbox.getEventId())
                .containsEntry("payload", outbox.getPayloadJson()));

        LOGGER.info(
                "failureDrill=redis-disconnect detectionMs={} recoveryMs={} "
                        + "retryAttempts={} commandCount={} duplicateDeliveries={} "
                        + "manualRepairs=0 finalStatus={}",
                detectionMillis,
                recoveryMillis,
                recovered.getRetryCount(),
                publishedCommands.size(),
                Math.max(0, publishedCommands.size() - 1),
                recovered.getStatus()
        );
    }

    private WorkflowOutbox persistPendingOutbox() {
        LocalDateTime now = LocalDateTime.now();
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setEventId("fault-drill-" + UUID.randomUUID());
        outbox.setAggregateId("redis-network-recovery");
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson("{\"node_id\":\"backend_engineer\"}");
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
        return outbox;
    }

    private void waitUntilEligible(LocalDateTime nextRetryAt) throws InterruptedException {
        long waitMillis = Math.max(
                0,
                Duration.between(LocalDateTime.now(), nextRetryAt).toMillis()
        );
        if (waitMillis > 0) {
            Thread.sleep(waitMillis + 25);
        }
    }

    @SuppressWarnings("deprecation")
    private static synchronized ToxiproxyContainer.ContainerProxy proxy() {
        if (redisProxy == null) {
            redisProxy = TOXIPROXY.getProxy(REDIS, REDIS_PORT);
        }
        return redisProxy;
    }
}
