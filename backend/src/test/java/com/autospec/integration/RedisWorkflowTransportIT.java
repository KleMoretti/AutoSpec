package com.autospec.integration;

import com.autospec.workflow.transport.RedisWorkflowCommandPublisher;
import com.autospec.workflow.transport.RedisWorkflowEventStreamClient;
import com.autospec.workflow.transport.WorkflowStreamEventMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RedisWorkflowTransportIT extends RedisIntegrationTestSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisWorkflowTransportIT.class);
    private static final Duration READ_BLOCK_TIMEOUT = Duration.ofMillis(250);
    private static final Duration WORKER_CLAIM_IDLE = Duration.ofMillis(100);
    private static final Duration WORKER_RECOVERY_RTO = Duration.ofSeconds(5);

    @Test
    void xaddConsumerCompetitionPendingAndAckUseRealRedis() throws Exception {
        String stream = uniqueName("workflow:commands");
        String group = uniqueName("workers");
        String eventId = UUID.randomUUID().toString();
        String payload = "{\"node_id\":\"backend_engineer\"}";
        RedisWorkflowCommandPublisher publisher = new RedisWorkflowCommandPublisher(redisTemplate);
        RedisWorkflowEventStreamClient client = streamClient();

        publisher.publish(stream, eventId, payload);

        assertThat(redisTemplate.opsForStream().size(stream)).isEqualTo(1);
        assertThat(redisTemplate.opsForStream().range(stream, Range.unbounded()))
                .singleElement()
                .satisfies(record -> assertThat(record.getValue())
                        .containsEntry("event_id", eventId)
                        .containsEntry("payload", payload));

        client.ensureGroup(stream, group);
        client.ensureGroup(stream, group);
        assertThat(redisTemplate.opsForStream().groups(stream))
                .singleElement()
                .satisfies(info -> assertThat(info.groupName()).isEqualTo(group));

        ConsumerRaceResult raceResult = raceConsumers(client, stream, group);

        assertThat(raceResult.winnerMessages()).singleElement()
                .extracting(WorkflowStreamEventMessage::payloadJson)
                .isEqualTo(payload);
        assertThat(raceResult.loserMessages()).isEmpty();

        WorkflowStreamEventMessage delivered = raceResult.winnerMessages().get(0);
        PendingMessagesSummary pending = redisTemplate.opsForStream().pending(stream, group);
        assertThat(pending.getTotalPendingMessages()).isEqualTo(1);
        assertThat(pending.getPendingMessagesPerConsumer())
                .containsOnlyKeys(raceResult.winner())
                .containsEntry(raceResult.winner(), 1L);

        client.acknowledge(stream, group, delivered.messageId());

        assertThat(redisTemplate.opsForStream().pending(stream, group).getTotalPendingMessages())
                .isZero();
    }

    @Test
    void workerExitBeforeAckIsRecoveredByAnotherConsumerWithinRto() throws Exception {
        String stream = uniqueName("workflow:events");
        String group = uniqueName("control-plane");
        String stalledConsumer = "stalled-worker";
        String recoveryConsumer = "recovery-worker";
        String payload = "{\"status\":\"SUCCEEDED\"}";
        RedisWorkflowCommandPublisher publisher = new RedisWorkflowCommandPublisher(redisTemplate);
        RedisWorkflowEventStreamClient client = streamClient();

        publisher.publish(stream, UUID.randomUUID().toString(), payload);
        client.ensureGroup(stream, group);
        WorkflowStreamEventMessage stalledMessage = client.read(
                stream,
                group,
                stalledConsumer,
                1
        ).get(0);

        assertThat(redisTemplate.opsForStream().pending(stream, group)
                .getPendingMessagesPerConsumer()).containsEntry(stalledConsumer, 1L);

        Instant failureAt = Instant.now();
        Thread.sleep(WORKER_CLAIM_IDLE.plusMillis(25).toMillis());
        List<WorkflowStreamEventMessage> reclaimed = client.claimStale(
                stream,
                group,
                recoveryConsumer,
                WORKER_CLAIM_IDLE,
                1
        );
        Instant detectedAt = Instant.now();

        assertThat(reclaimed).singleElement()
                .satisfies(message -> {
                    assertThat(message.messageId()).isEqualTo(stalledMessage.messageId());
                    assertThat(message.payloadJson()).isEqualTo(payload);
                });
        PendingMessages pending = redisTemplate.opsForStream().pending(
                stream,
                group,
                Range.unbounded(),
                10
        );
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getIdAsString()).isEqualTo(stalledMessage.messageId());
        assertThat(pending.get(0).getConsumerName()).isEqualTo(recoveryConsumer);
        assertThat(pending.get(0).getTotalDeliveryCount()).isEqualTo(2);
        assertThat(redisTemplate.opsForStream().size(stream)).isEqualTo(1);

        client.acknowledge(stream, group, stalledMessage.messageId());

        Instant recoveredAt = Instant.now();
        long finalPendingCount = redisTemplate.opsForStream()
                .pending(stream, group)
                .getTotalPendingMessages();
        FailureDrillObservation observation = new FailureDrillObservation(
                Duration.between(failureAt, detectedAt).toMillis(),
                Duration.between(failureAt, recoveredAt).toMillis(),
                pending.get(0).getTotalDeliveryCount() - 1,
                finalPendingCount
        );
        LOGGER.info(
                "failureDrill=worker-exit-before-ack detectionMs={} recoveryMs={} "
                        + "duplicateDeliveries={} finalPendingCount={}",
                observation.detectionMillis(),
                observation.recoveryMillis(),
                observation.duplicateDeliveries(),
                observation.finalPendingCount()
        );

        assertThat(observation.detectionMillis()).isLessThan(WORKER_RECOVERY_RTO.toMillis());
        assertThat(observation.recoveryMillis()).isLessThan(WORKER_RECOVERY_RTO.toMillis());
        assertThat(observation.duplicateDeliveries()).isEqualTo(1);
        assertThat(observation.finalPendingCount()).isZero();
    }

    @Test
    void commandPublisherApproximatelyBoundsStreamLength() {
        String stream = uniqueName("workflow:bounded");
        RedisWorkflowCommandPublisher publisher =
                new RedisWorkflowCommandPublisher(redisTemplate, 10);

        for (int index = 0; index < 250; index++) {
            publisher.publish(
                    stream,
                    "event-" + index,
                    "{\"sequence\":" + index + "}"
            );
        }

        assertThat(redisTemplate.opsForStream().size(stream))
                .isPositive()
                .isLessThanOrEqualTo(110);
    }

    private RedisWorkflowEventStreamClient streamClient() {
        return new RedisWorkflowEventStreamClient(redisTemplate, READ_BLOCK_TIMEOUT);
    }

    private ConsumerRaceResult raceConsumers(
            RedisWorkflowEventStreamClient client,
            String stream,
            String group
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<WorkflowStreamEventMessage>> first = executor.submit(
                    () -> readAfterStart(client, stream, group, "worker-a", ready, start)
            );
            Future<List<WorkflowStreamEventMessage>> second = executor.submit(
                    () -> readAfterStart(client, stream, group, "worker-b", ready, start)
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<WorkflowStreamEventMessage> firstMessages = first.get(5, TimeUnit.SECONDS);
            List<WorkflowStreamEventMessage> secondMessages = second.get(5, TimeUnit.SECONDS);
            assertThat(firstMessages.size() + secondMessages.size()).isEqualTo(1);
            if (firstMessages.isEmpty()) {
                return new ConsumerRaceResult(
                        "worker-b",
                        secondMessages,
                        firstMessages
                );
            }
            return new ConsumerRaceResult("worker-a", firstMessages, secondMessages);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<WorkflowStreamEventMessage> readAfterStart(
            RedisWorkflowEventStreamClient client,
            String stream,
            String group,
            String consumer,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("consumer race did not start");
        }
        return client.read(stream, group, consumer, 1);
    }

    private static String uniqueName(String prefix) {
        return prefix + ":" + UUID.randomUUID();
    }

    private record ConsumerRaceResult(
            String winner,
            List<WorkflowStreamEventMessage> winnerMessages,
            List<WorkflowStreamEventMessage> loserMessages
    ) {
    }

    private record FailureDrillObservation(
            long detectionMillis,
            long recoveryMillis,
            long duplicateDeliveries,
            long finalPendingCount
    ) {
    }
}
