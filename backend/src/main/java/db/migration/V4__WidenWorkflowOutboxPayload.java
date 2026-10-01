package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;

/** Widen outbox command payloads produced by large multi-agent contexts. */
public class V4__WidenWorkflowOutboxPayload extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean h2 = connection.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT)
                .contains("h2");
        String sql = h2
                ? "ALTER TABLE workflow_outbox ALTER COLUMN payload_json SET DATA TYPE LONGTEXT"
                : "ALTER TABLE workflow_outbox MODIFY COLUMN payload_json LONGTEXT";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
