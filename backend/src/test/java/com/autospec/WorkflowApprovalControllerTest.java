package com.autospec;

import com.autospec.controller.WorkflowApprovalController;
import com.autospec.dto.WorkflowApprovalResponse;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowApprovalService;
import com.autospec.workflow.runtime.DagCompiler;
import com.autospec.workflow.runtime.WorkflowSnapshotParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowApprovalControllerTest {

    @Test
    void listReturnsAllowedActionsFromFrozenWorkflowSnapshot() {
        WorkflowApprovalService approvalService = mock(WorkflowApprovalService.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowNodeRunMapper nodeRunMapper = mock(WorkflowNodeRunMapper.class);
        ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
        WorkflowApprovalController controller = new WorkflowApprovalController(
                approvalService,
                runMapper,
                nodeRunMapper,
                projectAccessService,
                new WorkflowSnapshotParser(new ObjectMapper()),
                new DagCompiler()
        );

        WorkflowApproval approval = new WorkflowApproval();
        approval.setId(7L);
        approval.setWorkflowRunId(11L);
        approval.setNodeRunId(13L);
        approval.setMode("AFTER_NODE");
        approval.setStatus("PENDING");
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeId("review_prd");
        WorkflowRun run = new WorkflowRun();
        run.setProjectId(5L);
        run.setWorkflowSnapshotJson("""
                {
                  "workflow_key":"approval-ui",
                  "version":"v5",
                  "nodes":[{
                    "node_id":"review_prd",
                    "depends_on":[],
                    "approval":{
                      "mode":"AFTER_NODE",
                      "allowed_actions":["APPROVE","EDIT_AND_APPROVE"]
                    }
                  }],
                  "edges":[]
                }
                """);
        when(projectAccessService.resolveUserId("session-1")).thenReturn(3L);
        when(approvalService.listByProjectId(5L)).thenReturn(List.of(approval));
        when(nodeRunMapper.selectById(13L)).thenReturn(nodeRun);
        when(runMapper.selectById(11L)).thenReturn(run);

        List<WorkflowApprovalResponse> response = controller.list(5L, "session-1");

        assertThat(response).singleElement().satisfies(value -> {
            assertThat(value.nodeId()).isEqualTo("review_prd");
            assertThat(value.allowedActions()).containsExactly("APPROVE", "EDIT_AND_APPROVE");
        });
        verify(projectAccessService).requireProjectRole(5L, 3L, "OWNER", "EDITOR", "VIEWER");
    }
}
