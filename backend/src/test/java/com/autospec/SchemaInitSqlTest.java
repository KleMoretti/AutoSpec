package com.autospec;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
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

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertThat(statement.execute(sql)).isTrue();
        }
    }
}
