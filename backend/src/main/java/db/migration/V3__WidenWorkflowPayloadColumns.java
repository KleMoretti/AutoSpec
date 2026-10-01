package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;

/**
 * Widen persisted workflow payloads after the immutable V1 baseline.
 *
 * <p>MySQL and H2 expose different ALTER COLUMN grammars, while the same
 * migration is used by the production container and the schema tests.</p>
 */
public class V3__WidenWorkflowPayloadColumns extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean h2 = connection.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT)
                .contains("h2");
        String alterInput = h2
                ? "ALTER TABLE workflow_node_run ALTER COLUMN input_json SET DATA TYPE LONGTEXT"
                : "ALTER TABLE workflow_node_run MODIFY COLUMN input_json LONGTEXT";
        String alterOutput = h2
                ? "ALTER TABLE workflow_node_run ALTER COLUMN output_json SET DATA TYPE LONGTEXT"
                : "ALTER TABLE workflow_node_run MODIFY COLUMN output_json LONGTEXT";
        String alterSnapshot = h2
                ? "ALTER TABLE workflow_run ALTER COLUMN workflow_snapshot_json SET DATA TYPE LONGTEXT"
                : "ALTER TABLE workflow_run MODIFY COLUMN workflow_snapshot_json LONGTEXT";
        try (Statement statement = connection.createStatement()) {
            statement.execute(alterInput);
            statement.execute(alterOutput);
            statement.execute(alterSnapshot);
        }
    }
}
