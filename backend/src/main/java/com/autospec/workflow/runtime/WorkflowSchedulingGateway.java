package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;

import java.util.List;

public interface WorkflowSchedulingGateway {
    List<WorkflowNodeRun> listNodeRuns(long workflowRunId);

    boolean reserveAndAppendCommand(WorkflowNodeRun nodeRun, QueuedNodeCommand command);
}
