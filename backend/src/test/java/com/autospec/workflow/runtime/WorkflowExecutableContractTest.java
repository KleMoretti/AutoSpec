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
                .isEqualTo("c4dc9b91ea583c82a0227f2a18df123718a18b082f9e234570e55a29e4983405");
    }
}
