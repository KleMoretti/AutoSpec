package com.autospec.integration;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.workflow.runtime.ReviewerReworkCoordinator;
import com.autospec.workflow.runtime.WorkflowArtifactProjector;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.autospec.workflow.transport.WorkflowEventConsumer;
import com.autospec.workflow.transport.WorkflowEventOutcome;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlWorkflowEventConsumerConcurrencyIT extends MySqlIntegrationTestSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            MySqlWorkflowEventConsumerConcurrencyIT.class
    );
    private static final long DEDUPLICATION_RTO_MILLIS = TimeUnit.SECONDS.toMillis(10);

    @Test
    void concurrentConsumersApplyTheSameEventOnlyOnce() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        createProcessedEventTable(dataSource);
        SqlSessionFactory sessionFactory = sessionFactory(dataSource);
        WorkflowNodeRunMapper nodeRunMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowRunReconciliationTrigger trigger = mock(WorkflowRunReconciliationTrigger.class);
        WorkflowRun run = new WorkflowRun();
        run.setId(7L);
        run.setStatus("RUNNING");
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setId(11L);
        nodeRun.setWorkflowRunId(7L);
        nodeRun.setExecutionId("7:backend_engineer:1:1");
        nodeRun.setStatus("QUEUED");
        when(runMapper.selectById(7L)).thenReturn(run);
        when(nodeRunMapper.selectById(11L)).thenReturn(nodeRun);
        when(nodeRunMapper.update(any(), any())).thenReturn(1);
        String eventId = "event-" + UUID.randomUUID();
        String payload = successPayload(eventId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<WorkflowEventOutcome> outcomes = List.of();
        long handlingMillis = -1;

        try {
            Future<WorkflowEventOutcome> first = executor.submit(() -> consumeWhenReleased(
                    sessionFactory, nodeRunMapper, runMapper, trigger, payload, ready, start
            ));
            Future<WorkflowEventOutcome> second = executor.submit(() -> consumeWhenReleased(
                    sessionFactory, nodeRunMapper, runMapper, trigger, payload, ready, start
            ));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            long handlingStartedAt = System.nanoTime();
            start.countDown();

            outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );
            handlingMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - handlingStartedAt
            );
            assertThat(outcomes).containsExactlyInAnyOrder(
                    WorkflowEventOutcome.ACCEPTED,
                    WorkflowEventOutcome.DUPLICATE
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        long finalProcessedRows = processedEventCount(dataSource, eventId);
        assertThat(handlingMillis).isLessThan(DEDUPLICATION_RTO_MILLIS);
        assertThat(finalProcessedRows).isEqualTo(1);
        verify(nodeRunMapper, times(1)).update(eq(null), any());
        verify(trigger, times(1)).reconcile(7L);
        verify(runMapper, times(1)).update(eq(null), any());

        LOGGER.info(
                "failureDrill=duplicate-terminal-event detectionMs={} recoveryMs={} "
                        + "duplicateDeliveries={} appliedResults={} manualRepairs=0 "
                        + "finalProcessedRows={}",
                handlingMillis,
                handlingMillis,
                outcomes.stream().filter(WorkflowEventOutcome.DUPLICATE::equals).count(),
                outcomes.stream().filter(WorkflowEventOutcome.ACCEPTED::equals).count(),
                finalProcessedRows
        );
    }

    private WorkflowEventOutcome consumeWhenReleased(
            SqlSessionFactory sessionFactory,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowRunMapper runMapper,
            WorkflowRunReconciliationTrigger trigger,
            String payload,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent consumers did not start in time");
        }
        try (SqlSession session = sessionFactory.openSession(false)) {
            WorkflowEventConsumer consumer = new WorkflowEventConsumer(
                    session.getMapper(ProcessedWorkflowEventMapper.class),
                    nodeRunMapper,
                    trigger,
                    mock(WorkflowFailureDecisionService.class),
                    new ObjectMapper(),
                    null,
                    WorkflowArtifactProjector.none(),
                    ReviewerReworkCoordinator.none(),
                    runMapper
            );
            WorkflowEventOutcome outcome = consumer.consume(payload);
            session.commit();
            return outcome;
        }
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(MYSQL.getDriverClassName());
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    private SqlSessionFactory sessionFactory(DriverManagerDataSource dataSource) {
        Environment environment = new Environment(
                "mysql-container",
                new JdbcTransactionFactory(),
                dataSource
        );
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ProcessedWorkflowEventMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void createProcessedEventTable(DriverManagerDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS processed_workflow_event (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        event_id VARCHAR(128) NOT NULL,
                        event_type VARCHAR(64) NOT NULL,
                        processed_at TIMESTAMP NOT NULL,
                        CONSTRAINT uk_processed_workflow_event_id UNIQUE (event_id)
                    )
                    """);
        }
    }

    private long processedEventCount(
            DriverManagerDataSource dataSource,
            String eventId
    ) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM processed_workflow_event WHERE event_id = ?"
             )) {
            statement.setString(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private String successPayload(String eventId) {
        return """
                {
                  "event_id":"%s",
                  "source_event_id":"command-1",
                  "event_type":"NODE_SUCCEEDED",
                  "workflow_run_id":7,
                  "node_run_id":11,
                  "node_id":"backend_engineer",
                  "revision":1,
                  "attempt":1,
                  "execution_id":"7:backend_engineer:1:1",
                  "duration_ms":12,
                  "output_payload":{"artifact":"generated-once"}
                }
                """.formatted(eventId);
    }
}
