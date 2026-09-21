package com.autospec;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ProjectMemoryMigrationTest {

    @Test
    void flywayCreatesVersionedProjectMemorySchemaAndRecallIndex() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:project_memory_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatCode(() -> execute(connection, """
                    select project_id, fact_type, fact_key, value_json, content_hash,
                           version, conflict_status, source_ref, valid_from_at,
                           valid_until_at, expires_at
                    from project_memory_fact where 1 = 0
                    """)).doesNotThrowAnyException();
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*) as index_count
                     from information_schema.indexes
                     where table_name = 'project_memory_fact'
                       and index_name in (
                         'idx_project_memory_recall',
                         'idx_project_memory_source_artifact'
                       )
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("index_count")).isEqualTo(2);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*) as constraint_count
                     from information_schema.table_constraints
                     where table_name = 'project_memory_fact'
                       and constraint_name = 'uk_project_memory_fact_version'
                       and constraint_type = 'UNIQUE'
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("constraint_count")).isEqualTo(1);
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertThat(statement.execute(sql)).isTrue();
        }
    }
}
