package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.autospec.workflow.spec.WorkflowSpecDocument;
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
        WorkflowReconciler reconciler = new WorkflowReconciler(gateway, new NodeReadinessEvaluator());

        ReconciliationResult result = reconciler.reconcile(7L, graph(2));

        assertThat(result.queuedNodes()).containsExactly("backend", "frontend");
        assertThat(gateway.commands).hasSize(2);
        assertThat(gateway.commands).extracting(QueuedNodeCommand::executionId)
                .doesNotHaveDuplicates();
    }

    @Test
    void omitsCommandWhenOptimisticReservationLosesTheRace() {
        InMemorySchedulingGateway gateway = new InMemorySchedulingGateway(List.of(
                nodeRun(11L, "backend", "PENDING", 0),
                nodeRun(12L, "frontend", "PENDING", 0)
        ));
        gateway.rejectedNode = "frontend";
        WorkflowReconciler reconciler = new WorkflowReconciler(gateway, new NodeReadinessEvaluator());

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
        WorkflowReconciler reconciler = new WorkflowReconciler(gateway, new NodeReadinessEvaluator());

        reconciler.reconcile(7L, graph(1));
        ReconciliationResult second = reconciler.reconcile(7L, graph(1));

        assertThat(second.queuedNodes()).isEmpty();
        assertThat(gateway.commands).hasSize(1);
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

    private WorkflowNodeRun nodeRun(Long id, String nodeId, String status, int lockVersion) {
        WorkflowNodeRun run = new WorkflowNodeRun();
        run.setId(id);
        run.setWorkflowRunId(7L);
        run.setNodeId(nodeId);
        run.setRevision(1);
        run.setAttempt(1);
        run.setStatus(status);
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
    }
}
