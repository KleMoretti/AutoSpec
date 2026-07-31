package com.autospec;

import com.autospec.controller.WorkflowDeadLetterController;
import com.autospec.dto.WorkflowDeadLetterResponse;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.AuditEventService;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowDeadLetterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDeadLetterControllerTest {

    @Test
    void listRequiresViewerAccessAndReturnsOnlySafeCommandSummary() {
        Fixture fixture = fixture();
        WorkflowOutbox outbox = deadLetter();
        when(fixture.deadLetterService().listByWorkflowRunId(anyLong(), any(), any()))
                .thenReturn(List.of(outbox));

        List<WorkflowDeadLetterResponse> response = fixture.controller().list(
                7L,
                "session-1",
                "DEAD_LETTER",
                25,
                0
        );

        assertThat(response).singleElement().satisfies(value -> {
            assertThat(value.workflowRunId()).isEqualTo(7L);
            assertThat(value.workflowNodeRunId()).isEqualTo(13L);
            assertThat(value.nodeId()).isEqualTo("backend_engineer");
            assertThat(value.executionId()).isEqualTo("7:backend_engineer:1:1");
            assertThat(value.eventId()).isEqualTo("command-1");
        });
        verify(fixture.projectAccessService())
                .requireProjectRole(5L, 3L, "OWNER", "EDITOR", "VIEWER");
    }

    @Test
    void replayRequiresEditorAccessAndWritesAuditEvent() {
        Fixture fixture = fixture();
        WorkflowOutbox outbox = deadLetter();
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        when(fixture.deadLetterService().replay(7L, 21L)).thenReturn(outbox);

        WorkflowDeadLetterResponse response = fixture.controller().replay(7L, 21L, "session-1");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.eventId()).isEqualTo("command-1");
        verify(fixture.projectAccessService()).requireProjectRole(5L, 3L, "OWNER", "EDITOR");
        verify(fixture.auditEventService()).record(
                5L,
                3L,
                "correlation-7",
                "WORKFLOW_DEAD_LETTER_REPLAYED",
                "WORKFLOW_OUTBOX",
                21L,
                "Dead-letter command approved for replay",
                "{\"workflowRunId\":7,\"status\":\"PENDING\"}"
        );
    }

    private Fixture fixture() {
        WorkflowDeadLetterService deadLetterService = mock(WorkflowDeadLetterService.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        WorkflowRun run = new WorkflowRun();
        run.setId(7L);
        run.setProjectId(5L);
        run.setCorrelationId("correlation-7");
        when(runMapper.selectById(7L)).thenReturn(run);
        when(projectAccessService.resolveUserId("session-1")).thenReturn(3L);
        return new Fixture(
                new WorkflowDeadLetterController(
                        deadLetterService,
                        runMapper,
                        projectAccessService,
                        auditEventService,
                        new ObjectMapper()
                ),
                deadLetterService,
                projectAccessService,
                auditEventService
        );
    }

    private WorkflowOutbox deadLetter() {
        LocalDateTime now = LocalDateTime.now();
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setId(21L);
        outbox.setEventId("command-1");
        outbox.setAggregateId("7");
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson("""
                {
                  "node_run_id": 13,
                  "node_id": "backend_engineer",
                  "execution_id": "7:backend_engineer:1:1",
                  "input_payload": {"secret": "not-returned"}
                }
                """);
        outbox.setStatus("DEAD_LETTER");
        outbox.setRetryCount(5);
        outbox.setLastErrorType("IllegalStateException");
        outbox.setLastErrorAt(now);
        outbox.setDeadLetteredAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }

    private record Fixture(
            WorkflowDeadLetterController controller,
            WorkflowDeadLetterService deadLetterService,
            ProjectAccessService projectAccessService,
            AuditEventService auditEventService
    ) {
    }
}
