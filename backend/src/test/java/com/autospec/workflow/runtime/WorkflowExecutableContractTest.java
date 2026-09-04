package com.autospec.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowExecutableContractTest {

    @Test
    void producesPythonCompatibleCanonicalFingerprint() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String snapshot = Files.readString(
                Path.of("..", "agent-engine", "contracts", "autospec-v5.workflow.json")
        );
        var spec = new WorkflowSnapshotParser(objectMapper).parse(snapshot);
        var node = spec.nodes().stream()
                .filter(value -> "product_manager".equals(value.nodeId()))
                .findFirst()
                .orElseThrow();

        WorkflowExecutableContract contract = WorkflowExecutableContract.from(
                spec.protocolVersion(),
                node,
                "ProductManagerAgent",
                "v1",
                objectMapper
        );

        assertThat(contract.contractHash())
                .isEqualTo("ba5c40bfc4c41edd4c4efa8695c8e676a0ba1ed57da68fa0b24f3bad58e7870f");
        assertThat(contract.protocolVersion()).isEqualTo(2);
        assertThat(contract.contextPolicy().path("version").asText()).isEqualTo("context-v2");
        assertThat(contract.modelPolicy().path("max_output_tokens").asInt()).isEqualTo(4000);
        WorkflowBudgetReservation reservation = WorkflowBudgetReservation.from(
                "7:product_manager:1:1",
                contract.contextPolicy(),
                contract.modelPolicy()
        );
        assertThat(reservation.totalTokens()).isEqualTo(32000L);
        assertThat(reservation.modelCalls()).isEqualTo(2);
    }
}
