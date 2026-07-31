package com.autospec.workflow.transport;

import com.autospec.entity.ProcessedWorkflowEvent;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.observability.WorkflowEventTracer;
import com.autospec.workflow.runtime.ReviewerReworkCoordinator;
import com.autospec.workflow.runtime.RetryPolicyEvaluator;
import com.autospec.workflow.runtime.WorkflowArtifactProjector;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEventConsumerTest {

    @Test
    void ignoresAnAlreadyProcessedEventId() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        when(processedMapper.insertIfAbsent(any(ProcessedWorkflowEvent.class))).thenReturn(0);
        WorkflowEventConsumer consumer = new WorkflowEventConsumer(
                processedMapper,
                nodeMapper,
                trigger,
                mock(WorkflowFailureDecisionService.class),
                new ObjectMapper(),
                null,
                WorkflowArtifactProjector.none(),
                ReviewerReworkCoordinator.none(),
                runMapper
        );

        WorkflowEventOutcome outcome = consumer.consume(successPayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.DUPLICATE);
        verify(nodeMapper, never()).update(any(), any());
        verify(trigger, never()).reconcile(any(Long.class));
        verify(runMapper).update(eq(null), any());
    }

    @Test
    void acceptsSuccessForCurrentExecutionAndTriggersReconciliation() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        when(processedMapper.insertIfAbsent(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(1);
        WorkflowEventConsumer consumer = consumer(processedMapper, nodeMapper, trigger);

        WorkflowEventOutcome outcome = consumer.consume(successPayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        verify(processedMapper).insertIfAbsent(any(ProcessedWorkflowEvent.class));
        verify(trigger).reconcile(7L);
    }

    @Test
    void exposesTraceContextWhileApplyingAnEventAndClearsItAfterward() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        AtomicReference<Map<String, String>> contextDuringUpdate = new AtomicReference<>();
        when(processedMapper.insertIfAbsent(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenAnswer(invocation -> {
            contextDuringUpdate.set(MDC.getCopyOfContextMap());
            return 1;
        });
        WorkflowEventConsumer consumer = consumer(processedMapper, nodeMapper, trigger);

        WorkflowEventOutcome outcome = consumer.consume(tracedSuccessPayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        assertThat(contextDuringUpdate.get())
                .containsEntry("traceId", "0123456789abcdef0123456789abcdef")
                .containsEntry("spanId", "0123456789abcdef")
                .containsEntry("correlationId", "correlation-7")
                .containsEntry("workflowRunId", "7")
                .containsEntry("nodeRunId", "11")
                .containsEntry("executionId", "7:fixture:1:1");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void continuesWorkerTraceWhileConsumingAnEvent() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(
                        io.opentelemetry.api.trace.propagation
                                .W3CTraceContextPropagator.getInstance()
                ))
                .build();
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        when(processedMapper.insertIfAbsent(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(1);
        WorkflowEventConsumer consumer = new WorkflowEventConsumer(
                processedMapper,
                nodeMapper,
                trigger,
                mock(WorkflowFailureDecisionService.class),
                new ObjectMapper(),
                null,
                WorkflowArtifactProjector.none(),
                ReviewerReworkCoordinator.none(),
                null,
                new WorkflowEventTracer(openTelemetry)
        );

        java.util.List<io.opentelemetry.sdk.trace.data.SpanData> spans;
        try {
            assertThat(consumer.consume(tracedSuccessPayload()))
                    .isEqualTo(WorkflowEventOutcome.ACCEPTED);
            spans = exporter.getFinishedSpanItems();
        } finally {
            provider.close();
        }

        assertThat(spans).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("workflow.event.consume");
            assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
            assertThat(span.getSpanContext().getTraceId())
                    .isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(span.getParentSpanContext().getSpanId())
                    .isEqualTo("0123456789abcdef");
            assertThat(span.getAttributes().asMap()).containsEntry(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                            "autospec.workflow.event.outcome"
                    ),
                    "ACCEPTED"
            );
        });
    }

    @Test
    void recordsButDoesNotApplyLateExecutionEvent() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        when(processedMapper.insertIfAbsent(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(0);
        WorkflowEventConsumer consumer = consumer(processedMapper, nodeMapper, trigger);

        WorkflowEventOutcome outcome = consumer.consume(successPayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.STALE);
        verify(trigger, never()).reconcile(any(Long.class));
    }

    @Test
    void heartbeatUpdatesLeaseWithoutTriggeringReconciliation() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        when(processedMapper.insertIfAbsent(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.update(any(), any())).thenReturn(1);
        WorkflowEventConsumer consumer = consumer(processedMapper, nodeMapper, trigger);

        WorkflowEventOutcome outcome = consumer.consume(heartbeatPayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        verify(trigger, never()).reconcile(any(Long.class));
    }

    @Test
    void retryableFailurePersistsRetryWaitAndNextRetryTime() {
        ProcessedWorkflowEventMapper processedMapper = mock(ProcessedWorkflowEventMapper.class);
        WorkflowNodeRunMapper nodeMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        WorkflowFailureDecisionService failureDecisions = mock(WorkflowFailureDecisionService.class);
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId(11L);
        nodeRun.setAttempt(1);
        LocalDateTime retryAt = LocalDateTime.of(2026, 7, 13, 12, 0, 1);
        when(processedMapper.insertIfAbsent(any(ProcessedWorkflowEvent.class))).thenReturn(1);
        when(nodeMapper.selectById(11L)).thenReturn(nodeRun);
        when(nodeMapper.update(any(), any())).thenReturn(1);
        when(failureDecisions.decide(eq(nodeRun), eq("MODEL_TIMEOUT"), any()))
                .thenReturn(new RetryPolicyEvaluator.Decision(
                        RetryPolicyEvaluator.Action.RETRY, retryAt, null
                ));
        WorkflowEventConsumer consumer = new WorkflowEventConsumer(
                processedMapper, nodeMapper, trigger, failureDecisions, new ObjectMapper()
        );

        WorkflowEventOutcome outcome = consumer.consume(failurePayload());

        assertThat(outcome).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        ArgumentCaptor<Wrapper<WorkflowNodeRun>> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(nodeMapper).update(eq(null), update.capture());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WorkflowNodeRun> values =
                (com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WorkflowNodeRun>) update.getValue();
        assertThat(values.getParamNameValuePairs().values())
                .contains("RETRY_WAIT", retryAt, "MODEL_TIMEOUT");
        verify(trigger).reconcile(7L);
    }

    @Test
    void atomicInsertSqlSupportsH2MysqlMode() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-event-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE processed_workflow_event (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_id VARCHAR(128) NOT NULL UNIQUE,
                        event_type VARCHAR(64) NOT NULL,
                        processed_at TIMESTAMP NOT NULL
                    )
                    """);
        }

        Environment environment = new Environment(
                "h2-mysql-mode",
                new JdbcTransactionFactory(),
                dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ProcessedWorkflowEventMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        ProcessedWorkflowEvent event = new ProcessedWorkflowEvent();
        event.setEventId("event-1");
        event.setEventType("NODE_SUCCEEDED");
        event.setProcessedAt(LocalDateTime.now());

        try (SqlSession session = sessionFactory.openSession(true)) {
            ProcessedWorkflowEventMapper mapper =
                    session.getMapper(ProcessedWorkflowEventMapper.class);

            assertThat(mapper.insertIfAbsent(event)).isEqualTo(1);
            assertThat(mapper.insertIfAbsent(event)).isZero();
        }
    }

    private WorkflowEventConsumer consumer(
            ProcessedWorkflowEventMapper processedMapper,
            WorkflowNodeRunMapper nodeMapper,
            WorkflowRunReconciliationTrigger trigger
    ) {
        WorkflowFailureDecisionService failureDecisions = mock(WorkflowFailureDecisionService.class);
        return new WorkflowEventConsumer(
                processedMapper, nodeMapper, trigger, failureDecisions, new ObjectMapper()
        );
    }

    private String successPayload() {
        return """
                {
                  "event_id":"7:fixture:1:1:succeeded",
                  "source_event_id":"command-1",
                  "event_type":"NODE_SUCCEEDED",
                  "workflow_run_id":7,
                  "node_run_id":11,
                  "node_id":"fixture",
                  "revision":1,
                  "attempt":1,
                  "execution_id":"7:fixture:1:1",
                  "duration_ms":12,
                  "output_payload":{"doubled":6}
                }
                """;
    }

    private String heartbeatPayload() {
        return successPayload()
                .replace("7:fixture:1:1:succeeded", "7:fixture:1:1:heartbeat:1")
                .replace("NODE_SUCCEEDED", "NODE_HEARTBEAT");
    }

    private String tracedSuccessPayload() {
        return successPayload().replace(
                "\"output_payload\":{\"doubled\":6}",
                "\"output_payload\":{\"doubled\":6},"
                        + "\"correlation_id\":\"correlation-7\","
                        + "\"traceparent\":\"00-0123456789abcdef0123456789abcdef-"
                        + "0123456789abcdef-01\""
        );
    }

    private String failurePayload() {
        return successPayload()
                .replace("7:fixture:1:1:succeeded", "7:fixture:1:1:failed:MODEL_TIMEOUT")
                .replace("NODE_SUCCEEDED", "NODE_FAILED")
                .replace("\"output_payload\":{\"doubled\":6}",
                        "\"error_code\":\"MODEL_TIMEOUT\",\"error_message\":\"timed out\"");
    }
}
