package com.autospec;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaInitSqlTest {

    @Test
    void flywayMigrationsCreateV2TablesAndColumns() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v2_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, "select id from project where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select id from review_issue where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select status, source_agent, parent_artifact_id, approved_at, updated_at from artifact where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select node_name, duration_ms, retry_of_task_id, prompt_version_id from agent_task where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select prompt_key, version, checksum, active from prompt_version where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select event_type, node_name, message, payload from agent_event where 1 = 0"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void flywayMigrationsCreateV3TablesAndColumns() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v3_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, "select username, display_name, password_hash, enabled from user_account where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select project_id, user_id, role from project_member where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select artifact_id, artifact_type, artifact_version from knowledge_document where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select document_id, chunk_index, retrieval_terms from knowledge_chunk where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select provider_key, model_name, agent_node from model_config where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select project_id, task_id, provider_key, model_name, status, duration_ms, score from model_invocation where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select project_id, status, manifest, completed_at from code_generation_job where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select project_id, file_name, media_type, encoding from export_file where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select project_id, workflow_key, version, graph_json from workflow_snapshot where 1 = 0"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void flywayMigrationsCreateProjectHistoryVisibilityIndexes() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:project_history_visibility_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*) as index_count
                     from information_schema.indexes
                     where index_name in (
                         'idx_project_user_id_id',
                         'idx_project_member_user_id_project_id'
                     )
                     """)) {
            resultSet.next();
            assertThat(resultSet.getInt("index_count")).isEqualTo(2);
        }
    }

    @Test
    void flywayMigrationsSeedV4RoadmapPlan() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v4_plan;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select a.title, a.version, a.format, a.status, length(a.content) as content_length
                     from project p
                     join artifact a on a.project_id = p.id
                     where p.name = 'AutoSpec V4 Plan'
                       and a.type = 'ROADMAP_PLAN'
                       and a.version = 4
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("title")).isEqualTo("AutoSpec V4 Agent Orchestration and Evaluation Plan");
            assertThat(resultSet.getInt("version")).isEqualTo(4);
            assertThat(resultSet.getString("format")).isEqualTo("MARKDOWN");
            assertThat(resultSet.getString("status")).isEqualTo("APPROVED");
            assertThat(resultSet.getInt("content_length")).isGreaterThan(100);
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsSeedBackendEngineeringDepthPlan() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:backend_depth_plan;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select a.title, a.version, a.format, a.status, a.content
                     from project p
                     join artifact a on a.project_id = p.id
                     where p.name = 'AutoSpec Backend Engineering Depth Plan'
                       and a.type = 'ROADMAP_PLAN'
                       and a.version = 5
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("title")).isEqualTo("AutoSpec Backend Engineering Depth Plan");
            assertThat(resultSet.getInt("version")).isEqualTo(5);
            assertThat(resultSet.getString("format")).isEqualTo("MARKDOWN");
            assertThat(resultSet.getString("status")).isEqualTo("APPROVED");
            assertThat(resultSet.getString("content"))
                    .contains("transactional orchestration")
                    .contains("observability")
                    .contains("Testcontainers")
                    .contains("GET /api/projects/{projectId}/exports/{exportFileId}")
                    .contains("ExportTransactionTest")
                    .contains("PROJECT_EXPORTED")
                    .contains("PaginationRequestTest")
                    .contains("PaginationRequest")
                    .contains("BackendApiContractTest")
                    .contains("OpenAPI")
                    .contains("BackendApiContractEndpointTest")
                    .contains("/api/contracts/openapi")
                    .contains("BackendDiagnosticsControllerTest")
                    .contains("/api/projects/{projectId}/diagnostics")
                    .contains("ProjectDiagnosticsResponse")
                    .contains("agent_task")
                    .contains("failedAgentTaskCount")
                    .contains("latestFailedExternalCallDurationMs")
                    .contains("latestFailedAgentTaskErrorMessage")
                    .contains("failedModelInvocationCount")
                    .contains("latestFailedModelInvocationErrorMessage")
                    .contains("promptVersionId")
                    .contains("GET /api/projects")
                    .contains("GET /api/projects/{projectId}")
                    .contains("ProjectResponse")
                    .contains("project history indexes")
                    .contains("idx_project_user_id_id")
                    .contains("idx_project_member_user_id_project_id")
                    .contains("workflow run history index")
                    .contains("idx_workflow_run_project_id_id")
                    .contains("workflow run diagnostics index")
                    .contains("idx_workflow_run_project_status_id")
                    .contains("workflow run recovery index")
                    .contains("idx_workflow_run_status_created_at")
                    .contains("workflow timeout recovery")
                    .contains("WorkflowRunServiceTest")
                    .contains("timeoutRunningRunsBefore")
                    .contains("workflow diagnostics")
                    .contains("runningWorkflowRunCount")
                    .contains("cancelledWorkflowRunCount")
                    .contains("reviewIssueCount")
                    .contains("blockingReviewIssueCount")
                    .contains("latestFailedWorkflowRunErrorMessage")
                    .contains("diagnostics authorization")
                    .contains("diagnostics tenant isolation")
                    .contains("workflow run cancellation")
                    .contains("WorkflowRunService.cancelRunningRun")
                    .contains("/api/projects/{projectId}/workflow-runs/{runId}/cancel")
                    .contains("workflow cancellation audit")
                    .contains("WORKFLOW_RUN_CANCELLED")
                    .contains("cancelled workflow diagnostics")
                    .contains("review issue diagnostics")
                    .contains("review issue diagnostics index")
                    .contains("idx_review_issue_project_status_severity_id")
                    .contains("agent task diagnostics index")
                    .contains("idx_agent_task_project_status_id")
                    .contains("model invocation diagnostics index")
                    .contains("idx_model_invocation_project_status_id")
                    .contains("external call log diagnostics index")
                    .contains("idx_external_call_log_project_status_id")
                    .contains("code generation job diagnostics index")
                    .contains("idx_code_generation_job_project_status_id")
                    .contains("codeGenerationJobCount")
                    .contains("latestFailedCodeGenerationJobErrorMessage")
                    .contains("evaluation diagnostics")
                    .contains("latestEvaluationOverallScore")
                    .contains("latestEvaluationGrade")
                    .contains("latestEvaluationIssueCount")
                    .contains("idx_artifact_project_type_id")
                    .contains("/api/projects/{projectId}/code-generation-jobs/{jobId}/cancel")
                    .contains("code generation cancellation audit")
                    .contains("CODE_GENERATION_JOB_CANCELLED")
                    .contains("CodeGenerationJobResponse")
                    .contains("/api/projects/{projectId}/workflow-runs")
                    .contains("/api/projects/{projectId}/external-calls")
                    .contains("WorkflowRunResponse")
                    .contains("RetryTaskResponse")
                    .contains("/api/projects/{projectId}/artifacts/{artifactId}/versions")
                    .contains("ApproveArtifactResponse")
                    .contains("/api/projects/{projectId}/progress")
                    .contains("/api/projects/{projectId}/workflow")
                    .contains("/api/projects/{projectId}/knowledge/sources")
                    .contains("ProjectProgressResponse")
                    .contains("WorkflowSnapshotResponse")
                    .contains("KnowledgeSourceResponse")
                    .contains("/api/prompts/active")
                    .contains("PromptVersionResponse");
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateWorkflowRunIdempotencyTable() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow_run_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, "select project_id, operation, idempotency_key, status, response_status, response_percent, completed_at from workflow_run where 1 = 0"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void flywayMigrationsCreateWorkflowRunHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow_run_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'workflow_run'
                       and index_name = 'idx_workflow_run_project_id_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateWorkflowRunDiagnosticsIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow_run_diagnostics_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'workflow_run'
                       and index_name = 'idx_workflow_run_project_status_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateWorkflowRunRecoveryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow_run_recovery_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'workflow_run'
                       and index_name = 'idx_workflow_run_status_created_at'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateAuditEventTable() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:audit_event_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, "select project_id, actor_user_id, event_type, entity_type, entity_id, message, metadata from audit_event where 1 = 0"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void flywayMigrationsCreateAuditEventHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:audit_event_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'audit_event'
                       and index_name = 'idx_audit_event_project_id_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateReviewIssueHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:review_issue_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'review_issue'
                       and index_name = 'idx_review_issue_project_id_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateReviewIssueDiagnosticsIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:review_issue_diagnostics_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'review_issue'
                       and index_name = 'idx_review_issue_project_status_severity_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateAgentTaskDiagnosticsIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:agent_task_diagnostics_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'agent_task'
                       and index_name = 'idx_agent_task_project_status_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateArtifactHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:artifact_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'artifact'
                       and index_name = 'idx_artifact_project_id_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateArtifactVersionHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:artifact_version_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'artifact'
                       and index_name = 'idx_artifact_project_type_version'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateArtifactTypeLatestIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:artifact_type_latest_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'artifact'
                       and index_name = 'idx_artifact_project_type_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateExternalCallLogTable() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:external_call_log_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, "select project_id, target_service, operation, status, duration_ms, request_context, error_message, started_at, completed_at from external_call_log where 1 = 0"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void flywayMigrationsCreateExternalCallLogHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:external_call_log_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'external_call_log'
                       and index_name = 'idx_external_call_log_project_id_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateExternalCallLogDiagnosticsIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:external_call_log_diagnostics_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'external_call_log'
                       and index_name = 'idx_external_call_log_project_status_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateExportFileHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:export_file_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'export_file'
                       and index_name = 'idx_export_file_project_id_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateAgentEventHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:agent_event_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'agent_event'
                       and index_name = 'idx_agent_event_project_id_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateCodeGenerationCancellationColumns() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:code_generation_cancel_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, "select cancelled_at from code_generation_job where 1 = 0"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void flywayMigrationsCreateCodeGenerationRetryColumns() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:code_generation_retry_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, "select retry_of_job_id from code_generation_job where 1 = 0"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void flywayMigrationsCreateCodeGenerationJobHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:code_generation_job_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'code_generation_job'
                       and index_name = 'idx_code_generation_job_project_id_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateCodeGenerationJobRecoveryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:code_generation_job_recovery_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'code_generation_job'
                       and index_name = 'idx_code_generation_job_status_created_at'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateCodeGenerationJobDiagnosticsIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:code_generation_job_diagnostics_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'code_generation_job'
                       and index_name = 'idx_code_generation_job_project_status_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateModelInvocationHistoryIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:model_invocation_history_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'model_invocation'
                       and index_name = 'idx_model_invocation_project_id_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateModelInvocationDiagnosticsIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:model_invocation_diagnostics_index_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'model_invocation'
                       and index_name = 'idx_model_invocation_project_status_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateModelInvocationWorkflowRunReference() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:model_invocation_workflow_run_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, "select workflow_run_id from model_invocation where 1 = 0"))
                    .doesNotThrowAnyException();
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select index_name
                     from information_schema.indexes
                     where table_name = 'model_invocation'
                       and index_name = 'idx_model_invocation_workflow_run_id'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void flywayMigrationsCreateCorrelationIdColumnsAndIndexes() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:correlation_id_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, "select correlation_id from workflow_run where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select correlation_id from audit_event where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select correlation_id from external_call_log where 1 = 0"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(connection, "select correlation_id from model_invocation where 1 = 0"))
                    .doesNotThrowAnyException();
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*) as index_count
                     from information_schema.indexes
                     where index_name in (
                         'idx_workflow_run_correlation_id',
                         'idx_audit_event_project_correlation_id',
                         'idx_external_call_log_project_correlation_id',
                         'idx_model_invocation_project_correlation_id'
                     )
                     """)) {
            resultSet.next();
            assertThat(resultSet.getInt("index_count")).isEqualTo(4);
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertThat(statement.execute(sql)).isTrue();
        }
    }
}
