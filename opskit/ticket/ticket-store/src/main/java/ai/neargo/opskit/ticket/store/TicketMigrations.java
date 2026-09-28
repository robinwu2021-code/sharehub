package ai.neargo.opskit.ticket.store;

import ai.neargo.opskit.commons.jdbc.Dialects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** 工单模块自己的 Flyway 实例与历史表（21/04 §一）。 */
public final class TicketMigrations {

    public static final String HISTORY_TABLE = "flyway_schema_history_ticket";

    private TicketMigrations() {
    }

    public static MigrateResult migrate(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).table(HISTORY_TABLE)
                .locations("classpath:db/ticket/" + Dialects.detect(dataSource).name())
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
    }
}
