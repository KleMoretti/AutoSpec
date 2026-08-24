package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.observability.WorkflowTraceContextFactory;
import com.autospec.workflow.spec.WorkflowEdgeDocument;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.autospec.workflow.spec.WorkflowSpecDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowReconcilerTest {

    @Test
    void queuesReadyNodesAndAppendsOneCommandPerSuccessfulReservation() {
        InMemorySchedulingGateway gateway = new InMemorySchedulingGateway(List.of(
                nodeRun(11L, "backend", "PENDING", 0),
                nodeRun(12L, "frontend", "PENDING", 0)
        ));
        WorkflowReconciler reconciler = reconciler(gateway);

        ReconciliationResult result = reconciler.reconcile(7L, graph(2));

        assertThat(result.queuedNodes()).containsExactly("backend", "frontend");
        assertThat(gateway.commands).hasSize(2);
        assertThat(gateway.commands).extracting(QueuedNodeCommand::executionId)
                .doesNotHaveDuplicates();
        assertThat(gateway.commands).allSatisfy(command -> {
            assertThat(command.handlerKey()).isEqualTo("FixtureAgent");
            assertThat(command.handlerVersion()).isEqualTo("v1");
            assertThat(command.timeoutMs()).isEqualTo(30000);
            assertThat(command.inputPayload().isObject()).isTrue();
            assertThat(command.correlationId()).isEqualTo("7");
            assertThat(WorkflowTraceContextFactory.isValidTraceparent(command.traceparent()))
                    .isTrue();
        });
    }

    @Test
    void omitsCommandWhenOptimisticReservationLosesTheRace() {
        InMemorySchedulingGateway gateway = new InMemorySchedulingGateway(List.of(
                nodeRun(11L, "backend", "PENDING", 0),
                nodeRun(12L, "frontend", "PENDING", 0)
        ));
        gateway.rejectedNode = "frontend";
        WorkflowReconciler reconciler = reconciler(gateway);

        ReconciliationResult result = reconciler.reconcile(7L, graph(2));

        assertThat(result.queuedNodes()).containsExactly("backend");
        assertThat(result.concurrentlyChangedNodes()).containsExactly("frontend");
        assertThat(gateway.commands).extracting(QueuedNodeCommand::nodeId)
                .containsExactly("backend");
    }

    @Test
    void repeatedReconciliationDoesNotQueueAnAlreadyQueuedNode() {
        InMemorySchedulingGateway gateway = new InMemorySchedulingGateway(List.of(
                nodeRun(11L, "backend", "PENDING", 0)
        ));
        WorkflowReconciler reconciler = reconciler(gateway);

        reconciler.reconcile(7L, graph(1));
        ReconciliationResult second = reconciler.reconcile(7L, graph(1));

        assertThat(second.queuedNodes()).isEmpty();
        assertThat(gateway.commands).hasSize(1);
    }

    @Test
    void persistsConditionalSkipInsteadOfDispatchingTarget() throws Exception {
        WorkflowNodeRun source = nodeRun(11L, "source", "SUCCEEDED", 0);
        source.setOutputJson("{\"approved\":false}");
        WorkflowNodeRun target = nodeRun(12L, "target", "PENDING", 0);
        InMemorySchedulingGateway gateway = new InMemorySchedulingGateway(List.of(source, target));
        var condition = new ObjectMapper().readTree(
                "{\"path\":\"$.approved\",\"operator\":\"EQ\",\"value\":true}"
        );
        CompiledWorkflow graph = new DagCompiler().compile(new WorkflowSpecDocument(
                "conditional-reconcile",
                "v5",
                2,
                List.of(
                        new WorkflowNodeDocument("source", List.of()),
                        new WorkflowNodeDocument("target", List.of())
                ),
                List.of(new WorkflowEdgeDocument(
                        "source", "target", "CONDITIONAL", condition
                )),
                List.of()
        ));

        ReconciliationResult result = reconciler(gateway).reconcile(7L, graph);

        assertThat(result.skippedNodes()).containsExactly("target");
        assertThat(target.getStatus()).isEqualTo("SKIPPED");
        assertThat(gateway.commands).isEmpty();
    }

    private CompiledWorkflow graph(int parallelism) {
        return new DagCompiler().compile(new WorkflowSpecDocument(
                "reconcile-test",
                "v5",
                parallelism,
                List.of(
                        new WorkflowNodeDocument("backend", List.of()),
                        new WorkflowNodeDocument("frontend", List.of())
                ),
                List.of(),
                List.of()
        ));
    }

    private WorkflowReconciler reconciler(WorkflowSchedulingGateway gateway) {
        return new WorkflowReconciler(gateway, new NodeReadinessEvaluator(), new ObjectMapper());
    }

    private WorkflowNodeRun nodeRun(Long id, String nodeId, String status, int lockVersion) {
        WorkflowNodeRun run = new WorkflowNodeRun();
        run.setId(id);
        run.setWorkflowRunId(7L);
        run.setNodeId(nodeId);
        run.setRevision(1);
        run.setAttempt(1);
        run.setStatus(status);
        run.setHandlerKey("FixtureAgent");
        run.setHandlerVersion("v1");
        run.setInputJson("{}");
        run.setLockVersion(lockVersion);
        return run;
    }

    private static class InMemorySchedulingGateway implements WorkflowSchedulingGateway {
        private final List<WorkflowNodeRun> nodeRuns = new ArrayList<>();
        private final List<QueuedNodeCommand> commands = new ArrayList<>();
        private String rejectedNode;

        private InMemorySchedulingGateway(List<WorkflowNodeRun> nodeRuns) {
            this.nodeRuns.addAll(nodeRuns);
        }

        @Override
        public List<WorkflowNodeRun> listNodeRuns(long workflowRunId) {
            return nodeRuns;
        }

        @Override
        public boolean reserveAndAppendCommand(WorkflowNodeRun nodeRun, QueuedNodeCommand command) {
            if (nodeRun.getNodeId().equals(rejectedNode) || !"PENDING".equals(nodeRun.getStatus())) {
                return false;
            }
            nodeRun.setStatus("QUEUED");
            nodeRun.setLockVersion(nodeRun.getLockVersion() + 1);
            nodeRun.setExecutionId(command.executionId());
            commands.add(command);
            return true;
        }

        @Override
        public boolean markSkipped(WorkflowNodeRun nodeRun, String reason) {
            if (!"PENDING".equals(nodeRun.getStatus())) {
                return false;
            }
            nodeRun.setStatus("SKIPPED");
            nodeRun.setErrorCode("CONDITION_NOT_MATCHED");
            nodeRun.setErrorMessage(reason);
            nodeRun.setLockVersion(nodeRun.getLockVersion() + 1);
            return true;
        }
    }
}
