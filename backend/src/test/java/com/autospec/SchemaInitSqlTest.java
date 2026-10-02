package com.autospec;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaInitSqlTest {
    @Test
    void baselineCreatesCurrentRuntimeAndOnlyCurrentWorkflow() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:baseline;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        try (var connection = source.getConnection()) {
            var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("5");

            Set<String> tables = new HashSet<>();
            try (var rows = connection.getMetaData().getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (rows.next()) tables.add(rows.getString("TABLE_NAME"));
            }
            assertThat(tables).contains("workflow_run", "workflow_node_run", "workflow_approval",
                    "workflow_execution_bundle", "workflow_tool_call_fact", "workflow_agent_step_fact",
                    "workflow_outbox", "processed_workflow_event", "workflow_event_dead_letter",
                    "artifact", "artifact_component", "artifact_trace_edge", "project_memory_fact",
                    "model_invocation", "knowledge_document", "knowledge_chunk", "user_session");
            assertThat(tables).doesNotContain("agent_task", "agent_event", "external_call_log", "workflow_snapshot");

            try (var statement = connection.createStatement()) {
                statement.executeQuery("select execution_bundle_hash, fencing_token, "
                        + "reserved_input_tokens, reserved_output_tokens, reserved_cost, reserved_model_calls from workflow_node_run where 1 = 0");
                statement.executeQuery("select input_json, output_json from workflow_node_run where 1 = 0");
                statement.executeQuery("select workflow_snapshot_json from workflow_run where 1 = 0");
                statement.executeQuery("select payload_json from workflow_outbox where 1 = 0");
                statement.executeQuery("select claim_owner, claim_until, claim_version from workflow_outbox where 1 = 0");
                statement.executeQuery("select upload_idempotency_key from artifact where 1 = 0");
                statement.executeQuery("select source_ref, content_hash, version, expires_at "
                        + ", trust_status from project_memory_fact where 1 = 0");
                statement.executeQuery("select stream_message_id, consumer_group, delivery_count "
                        + "from workflow_event_delivery_attempt where 1 = 0");
                statement.executeQuery("select candidate_hash, verification_fact_ref "
                        + "from workflow_agent_step_fact where 1 = 0");
                try (var rows = statement.executeQuery("select version, status, spec_json from workflow_version")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("version")).isEqualTo("pm-schema-repair-v12");
                    assertThat(rows.getString("status")).isEqualTo("PUBLISHED");
                    assertThat(rows.getString("spec_json")).contains("EvaluatorAgent_v4", "BackendEngineerAgent_v6");
                    assertThat(rows.next()).isFalse();
                }
            }

            Set<String> indexes = new HashSet<>();
            for (String table : Set.of("workflow_node_run", "workflow_outbox", "project_memory_fact", "artifact")) {
                try (var rows = connection.getMetaData().getIndexInfo(null, "public", table, false, false)) {
                    while (rows.next()) indexes.add(rows.getString("INDEX_NAME"));
                }
            }
            assertThat(indexes).anyMatch(name -> name != null && name.startsWith("uk_workflow_node_run_execution_id"));
            assertThat(indexes).contains("idx_workflow_outbox_publish", "idx_workflow_outbox_claim",
                    "idx_project_memory_recall", "idx_project_memory_trusted_recall");
            assertThat(indexes).anyMatch(name -> name != null && name.startsWith("uk_artifact_project_type_version"));
            assertThat(indexes).contains("uk_artifact_project_upload_idempotency");
        }
    }
}
