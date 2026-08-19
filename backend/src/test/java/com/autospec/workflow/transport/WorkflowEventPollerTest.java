package com.autospec.workflow.transport;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowEventPollerTest {

    @Test
    void acknowledgesOnlyAfterDatabaseConsumerAcceptsMessage() {
        FakeEventStreamClient client = new FakeEventStreamClient();
        client.freshMessages = List.of(
                new WorkflowStreamEventMessage("1-0", "{\"event_id\":\"e1\"}")
        );
        RecordingEventHandler handler = new RecordingEventHandler();
        WorkflowEventPoller poller = new WorkflowEventPoller(client, handler, "control-1");

        int processed = poller.pollOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(handler.payloads).containsExactly("{\"event_id\":\"e1\"}");
        assertThat(client.acknowledged).containsExactly("1-0");
    }

    @Test
    void leavesMessagePendingWhenDatabaseConsumerFails() {
        FakeEventStreamClient client = new FakeEventStreamClient();
        client.reclaimedMessages = List.of(new WorkflowStreamEventMessage("2-0", "{}"));
        WorkflowEventMessageHandler handler = payload -> {
            throw new IllegalStateException("database unavailable");
        };
        WorkflowEventPoller poller = new WorkflowEventPoller(
                client, handler, "control-1", 10
        );

        assertThatThrownBy(poller::pollOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        assertThat(client.acknowledged).isEmpty();
    }

    @Test
    void quarantinesInvalidEventAndContinuesWithRemainingBatch() {
        FakeEventStreamClient client = new FakeEventStreamClient();
        client.freshMessages = List.of(
                new WorkflowStreamEventMessage("4-0", "not-json"),
                new WorkflowStreamEventMessage("5-0", "{\"event_id\":\"valid\"}")
        );
        ArrayList<String> handled = new ArrayList<>();
        ArrayList<String> quarantined = new ArrayList<>();
        WorkflowEventMessageHandler handler = payload -> {
            if ("not-json".equals(payload)) {
                throw new InvalidWorkflowEventException("invalid event JSON");
            }
            handled.add(payload);
        };
        WorkflowEventPoller poller = new WorkflowEventPoller(
                client,
                handler,
                "control-1",
                10,
                Duration.ofSeconds(30),
                WorkflowTransportMetrics.isolated(),
                (message, failure) -> quarantined.add(
                        message.messageId() + ":" + failure.getMessage()
                )
        );

        int processed = poller.pollOnce();

        assertThat(processed).isEqualTo(2);
        assertThat(quarantined).containsExactly("4-0:invalid event JSON");
        assertThat(handled).containsExactly("{\"event_id\":\"valid\"}");
        assertThat(client.acknowledged).containsExactly("4-0", "5-0");
    }

    @Test
    void reclaimsStaleMessagesBeforeReadingFreshMessages() {
        FakeEventStreamClient client = new FakeEventStreamClient();
        client.reclaimedMessages = List.of(
                new WorkflowStreamEventMessage("2-0", "{\"event_id\":\"stale\"}")
        );
        client.freshMessages = List.of(
                new WorkflowStreamEventMessage("3-0", "{\"event_id\":\"fresh\"}")
        );
        RecordingEventHandler handler = new RecordingEventHandler();
        WorkflowEventPoller poller = new WorkflowEventPoller(
                client,
                handler,
                "control-2",
                5,
                Duration.ofSeconds(45)
        );

        int processed = poller.pollOnce();

        assertThat(processed).isEqualTo(2);
        assertThat(handler.payloads).containsExactly(
                "{\"event_id\":\"stale\"}",
                "{\"event_id\":\"fresh\"}"
        );
        assertThat(client.operations).containsExactly(
                "ensure",
                "claim:control-2:45000:5",
                "read:control-2:5",
                "ack:2-0",
                "ack:3-0"
        );
        assertThat(client.acknowledged).containsExactly("2-0", "3-0");
    }

    private static class FakeEventStreamClient implements WorkflowEventStreamClient {
        private List<WorkflowStreamEventMessage> reclaimedMessages = List.of();
        private List<WorkflowStreamEventMessage> freshMessages = List.of();
        private final ArrayList<String> acknowledged = new ArrayList<>();
        private final ArrayList<String> operations = new ArrayList<>();

        @Override
        public void ensureGroup(String stream, String group) {
            operations.add("ensure");
        }

        @Override
        public List<WorkflowStreamEventMessage> claimStale(
                String stream,
                String group,
                String consumer,
                Duration minimumIdleTime,
                int count
        ) {
            operations.add(
                    "claim:" + consumer + ":" + minimumIdleTime.toMillis() + ":" + count
            );
            return reclaimedMessages;
        }

        @Override
        public List<WorkflowStreamEventMessage> read(
                String stream, String group, String consumer, int count
        ) {
            operations.add("read:" + consumer + ":" + count);
            return freshMessages;
        }

        @Override
        public void acknowledge(String stream, String group, String messageId) {
            acknowledged.add(messageId);
            operations.add("ack:" + messageId);
        }
    }

    private static class RecordingEventHandler implements WorkflowEventMessageHandler {
        private final ArrayList<String> payloads = new ArrayList<>();

        @Override
        public void handle(String payloadJson) {
            payloads.add(payloadJson);
        }
    }
}
