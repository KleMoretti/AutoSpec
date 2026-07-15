package com.autospec.workflow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueuedNodeCommandTest {

    @Test
    void serializesPythonWorkerCompatibleEnvelope() throws Exception {
        QueuedNodeCommand command = new QueuedNodeCommand(
                "command-1", 7L, 11L, "backend", 1, 2, "7:backend:1:2",
                "BackendEngineerAgent", "v2", 45000,
                new ObjectMapper().readTree("{\"requirement\":\"build API\"}")
        );

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(command));

        assertThat(json.path("event_id").asText()).isEqualTo("command-1");
        assertThat(json.path("workflow_run_id").asLong()).isEqualTo(7L);
        assertThat(json.path("node_run_id").asLong()).isEqualTo(11L);
        assertThat(json.path("execution_id").asText()).isEqualTo("7:backend:1:2");
        assertThat(json.path("handler_key").asText()).isEqualTo("BackendEngineerAgent");
        assertThat(json.path("handler_version").asText()).isEqualTo("v2");
        assertThat(json.path("timeout_ms").asInt()).isEqualTo(45000);
        assertThat(json.path("input_payload").path("requirement").asText())
                .isEqualTo("build API");
        assertThat(json.has("workflowRunId")).isFalse();
    }
}
