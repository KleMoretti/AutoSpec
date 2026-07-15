package com.autospec.workflow.transport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowEventPollerTest {

    @Test
    void acknowledgesOnlyAfterDatabaseConsumerAcceptsMessage() {
        FakeEventStreamClient client = new FakeEventStreamClient();
        client.messages = List.of(new WorkflowStreamEventMessage("1-0", "{\"event_id\":\"e1\"}"));
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
        client.messages = List.of(new WorkflowStreamEventMessage("2-0", "{}"));
        WorkflowEventMessageHandler handler = payload -> {
            throw new IllegalStateException("database unavailable");
        };
        WorkflowEventPoller poller = new WorkflowEventPoller(client, handler, "control-1");

        assertThatThrownBy(poller::pollOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        assertThat(client.acknowledged).isEmpty();
    }

    private static class FakeEventStreamClient implements WorkflowEventStreamClient {
        private List<WorkflowStreamEventMessage> messages = List.of();
        private final java.util.ArrayList<String> acknowledged = new java.util.ArrayList<>();

        @Override
        public void ensureGroup(String stream, String group) {
        }

        @Override
        public List<WorkflowStreamEventMessage> read(
                String stream, String group, String consumer, int count
        ) {
            return messages;
        }

        @Override
        public void acknowledge(String stream, String group, String messageId) {
            acknowledged.add(messageId);
        }
    }

    private static class RecordingEventHandler implements WorkflowEventMessageHandler {
        private final java.util.ArrayList<String> payloads = new java.util.ArrayList<>();

        @Override
        public void handle(String payloadJson) {
            payloads.add(payloadJson);
        }
    }
}
