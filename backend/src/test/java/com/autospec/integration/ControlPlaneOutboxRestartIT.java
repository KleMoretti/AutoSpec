package com.autospec.integration;

import com.autospec.AutoSpecApplication;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.workflow.transport.WorkflowOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ControlPlaneOutboxRestartIT {
    private static final Logger LOGGER = LoggerFactory.getLogger(ControlPlaneOutboxRestartIT.class);
    private static final int REDIS_PORT = 6379;
    private static final Duration RESTART_RECOVERY_RTO = Duration.ofSeconds(15);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.4")
    )
            .withDatabaseName("autospec_restart_drill")
            .withUsername("autospec")
            .withPassword("autospec");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")
    ).withExposedPorts(REDIS_PORT);

    @Test
    void pendingOutboxSurvivesControlPlaneRestartAndPublishesExactlyOnce() {
        WorkflowOutbox pending;
        try (ConfigurableApplicationContext first = startControlPlane()) {
            WorkflowOutboxMapper mapper = first.getBean(WorkflowOutboxMapper.class);
            pending = pendingOutbox();
            mapper.insert(pending);
            assertThat(mapper.selectById(pending.getId()).getStatus()).isEqualTo("PENDING");
        }

        Instant restartAt = Instant.now();
        try (ConfigurableApplicationContext second = startControlPlane()) {
            WorkflowOutboxMapper mapper = second.getBean(WorkflowOutboxMapper.class);
            WorkflowOutboxPublisher publisher = second.getBean(WorkflowOutboxPublisher.class);
            StringRedisTemplate redis = second.getBean(StringRedisTemplate.class);
            WorkflowOutbox recoveredPending = mapper.selectById(pending.getId());
            long detectionMillis = Duration.between(restartAt, Instant.now()).toMillis();

            assertThat(recoveredPending.getStatus()).isEqualTo("PENDING");
            assertThat(publisher.publishPending(10)).isEqualTo(1);
            assertThat(publisher.publishPending(10)).isZero();

            long recoveryMillis = Duration.between(restartAt, Instant.now()).toMillis();
            WorkflowOutbox published = mapper.selectById(pending.getId());
            long commandCount = redis.opsForStream()
                    .range(WorkflowOutboxPublisher.COMMAND_STREAM, Range.unbounded())
                    .stream()
                    .filter(record -> pending.getEventId().equals(record.getValue().get("event_id")))
                    .count();
            LOGGER.info(
                    "failureDrill=control-plane-exit-before-outbox-publish detectionMs={} "
                            + "recoveryMs={} commandCount={} manualRepairs=0 finalStatus={}",
                    detectionMillis,
                    recoveryMillis,
                    commandCount,
                    published.getStatus()
            );

            assertThat(detectionMillis).isLessThan(RESTART_RECOVERY_RTO.toMillis());
            assertThat(recoveryMillis).isLessThan(RESTART_RECOVERY_RTO.toMillis());
            assertThat(commandCount).isEqualTo(1);
            assertThat(published.getStatus()).isEqualTo("PUBLISHED");
            assertThat(published.getPublishedAt()).isNotNull();
        }
    }

    private ConfigurableApplicationContext startControlPlane() {
        return new SpringApplicationBuilder(AutoSpecApplication.class)
                .profiles("integration-test")
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.datasource.driver-class-name=" + MYSQL.getDriverClassName(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(REDIS_PORT),
                        "--spring.flyway.baseline-on-migrate=false",
                        "--autospec.workflow.outbox.enabled=false",
                        "--autospec.workflow.recovery.enabled=false",
                        "--autospec.workflow.events.polling.enabled=false",
                        "--autospec.observability.workflow-backlog.enabled=false",
                        "--management.tracing.enabled=false"
                );
    }

    private WorkflowOutbox pendingOutbox() {
        LocalDateTime now = LocalDateTime.now();
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setEventId("restart-drill-" + UUID.randomUUID());
        outbox.setAggregateId("control-plane-restart");
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson("{\"node_id\":\"backend_engineer\"}");
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }
}
