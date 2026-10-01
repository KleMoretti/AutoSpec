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
    @Test
    void parallelSharedContractVersionHasIndependentEngineeringBranches() throws Exception {
        var mapper = new ObjectMapper();
        var snapshot = Files.readString(Path.of("..", "agent-engine", "contracts",
                "autospec-v5-parallel.workflow.json"));
        var spec = new WorkflowSnapshotParser(mapper).parse(snapshot);
        WorkflowExecutableContractValidator.validate(spec);
        var graph = new DagCompiler().compile(spec);
        assertThat(graph.topologicalLayers()).contains(java.util.List.of("backend_engineer", "frontend_engineer"));
        assertThat(graph.predecessors().get("frontend_engineer")).containsExactly("architect");
        assertThat(graph.predecessors().get("reviewer"))
                .containsExactlyInAnyOrder("backend_engineer", "frontend_engineer");
    }

    @Test
    void pmSchemaRepairCandidateValidatesAndReservesTwoFlashCalls() throws Exception {
        var mapper = new ObjectMapper();
        var snapshot = Files.readString(Path.of("..", "agent-engine", "contracts",
                "autospec-pm-schema-repair-v12.workflow.json"));
        var spec = new WorkflowSnapshotParser(mapper).parse(snapshot);
        WorkflowExecutableContractValidator.validate(spec);
        var node = spec.nodes().stream()
                .filter(value -> "product_manager".equals(value.nodeId()))
                .findFirst()
                .orElseThrow();

        assertThat(node.modelPolicy().path("provider_key").asText()).isEqualTo("deepseek");
        assertThat(node.modelPolicy().path("model_name").asText()).isEqualTo("deepseek-flash");
        assertThat(node.modelPolicy().path("max_output_tokens").asInt()).isEqualTo(8000);
        assertThat(node.modelPolicy().path("structured_output_repair").path("max_repairs").asInt())
                .isEqualTo(1);

        WorkflowBudgetReservation reservation = WorkflowBudgetReservation.from(
                "19:product_manager:1:1", node.contextPolicy(), node.modelPolicy()
        );
        assertThat(reservation.inputTokens()).isEqualTo(24000L);
        assertThat(reservation.outputTokens()).isEqualTo(16000L);
        assertThat(reservation.estimatedCost()).isEqualByComparingTo("0.176000");
    }

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
