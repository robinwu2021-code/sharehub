package ai.neargo.opskit.process.store;

import ai.neargo.opskit.commons.jdbc.Dialects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** 流程模块自己的 Flyway 实例与历史表（21/04 §一）。 */
public final class ProcessMigrations {

    public static final String HISTORY_TABLE = "flyway_schema_history_process";

    private ProcessMigrations() {
    }

    public static MigrateResult migrate(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).table(HISTORY_TABLE)
                .locations("classpath:db/process/" + Dialects.detect(dataSource).name())
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
    }
}
