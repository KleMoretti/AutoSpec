package com.autospec.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowExecutableContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"v5", "v6"})
    void diagnosticPinsThinkingModeAndRejectsInvalidValues(String version) throws Exception {
        var mapper = new ObjectMapper();
        var parser = new WorkflowSnapshotParser(mapper);
        var document = mapper.readTree(Files.readString(Path.of("..", "agent-engine", "contracts",
                "autospec-v5-agent-execution-" + version + "-d.workflow.json")));
        var spec = parser.parse(document.toString());
        assertThat(spec.nodes()).hasSize(6);
        WorkflowExecutableContractValidator.validate(spec);
        var policy = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("nodes").get(0).path("model_policy");
        assertThat(policy.path("thinking_mode").asText()).isEqualTo("disabled");
        policy.put("thinking_mode", "unknown");
        assertThatThrownBy(() -> WorkflowExecutableContractValidator.validate(parser.parse(document.toString())))
                .hasMessageContaining("thinking_mode");
    }

    @Test
    void v4ExperimentsPinPricedFlashAndReserveRealMoney() throws Exception {
        var mapper = new ObjectMapper();
        var parser = new WorkflowSnapshotParser(mapper);
        for (String group : java.util.List.of("a", "b", "c", "d")) {
            var spec = parser.parse(Files.readString(Path.of("..", "agent-engine", "contracts",
                    "autospec-v5-agent-execution-v4-" + group + ".workflow.json")));
            assertThat(spec.nodes()).hasSize(6);
            WorkflowExecutableContractValidator.validate(spec);
            for (var node : spec.nodes()) {
                if (node.nodeId().equals("evaluator")) {
                    continue;
                }
                assertThat(node.modelPolicy().path("provider_key").asText()).isEqualTo("deepseek");
                assertThat(node.modelPolicy().path("model_name").asText()).isEqualTo("deepseek-v4-flash");
                assertThat(node.retryPolicy().path("max_attempts").asInt()).isEqualTo(1);
                var reservation = WorkflowBudgetReservation.from("priced-" + node.nodeId(),
                        node.contextPolicy(), node.modelPolicy());
                assertThat(reservation.estimatedCost()).isPositive();
                if (node.nodeId().equals("backend_engineer")) {
                    assertThat(reservation.estimatedCost()).isEqualByComparingTo("0.476000");
                }
            }
        }
    }

    @Test
    void v3ExperimentsUseVersionedEvaluatorWithoutChangingTheSixNodeDag() throws Exception {
        var mapper = new ObjectMapper();
        var parser = new WorkflowSnapshotParser(mapper);
        for (String group : java.util.List.of("a", "b", "c", "d")) {
            var spec = parser.parse(Files.readString(Path.of("..", "agent-engine", "contracts",
                    "autospec-v5-agent-execution-v3-" + group + ".workflow.json")));
            assertThat(spec.nodes()).hasSize(6);
            WorkflowExecutableContractValidator.validate(spec);
            var evaluator = spec.nodes().stream().filter(n -> n.nodeId().equals("evaluator")).findFirst().orElseThrow();
            assertThat(evaluator.agentName()).isEqualTo("EvaluatorAgent_v2");
            assertThat(evaluator.inputSchema()).isEqualTo("EvaluationRuntimeInput");
            assertThat(evaluator.outputSchema()).isEqualTo("EvaluationReport");
        }
    }

    @Test
    void v2ExperimentBudgetsAllowDeclaredReplansAndRejectUnreachableLoop() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String group : java.util.List.of("a", "b", "c", "d")) {
            String raw = Files.readString(Path.of("..", "agent-engine", "contracts",
                    "autospec-v5-agent-execution-v2-" + group + ".workflow.json"));
            var parser = new WorkflowSnapshotParser(mapper);
            assertThat(parser.parse(raw).nodes()).hasSize(6);
            WorkflowExecutableContractValidator.validate(parser.parse(raw));
            if (group.equals("d")) {
                var document = mapper.readTree(raw);
                for (var node : document.path("nodes")) {
                    if (node.path("node_id").asText().equals("backend_engineer")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("model_policy"))
                                .put("max_calls", 2);
                    }
                }
                assertThatThrownBy(() -> WorkflowExecutableContractValidator.validate(parser.parse(document.toString())))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("steps and model calls");
            }
        }
    }

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
                .isEqualTo("827a6eb93885f2022adff04592a139312a66cf800bb6afd71ecd18e95330c622");
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
