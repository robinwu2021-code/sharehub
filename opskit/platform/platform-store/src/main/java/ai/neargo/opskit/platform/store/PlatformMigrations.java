package ai.neargo.opskit.platform.store;

import ai.neargo.opskit.commons.jdbc.Dialects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 平台模块自己的 Flyway 实例（21/04 §一：每个模块独立实例与历史表）。
 *
 * <p>不用 Spring Boot 的 Flyway 自动配置：它只管一个实例、一张历史表，
 * 三个模块共用会互相踩版本号；而 {@code {vendor}} 占位也是 Boot 的特性，纯 Flyway 不认，所以按方言自己选目录。
 */
public final class PlatformMigrations {

    public static final String HISTORY_TABLE = "flyway_schema_history_platform";

    private PlatformMigrations() {
    }

    public static MigrateResult migrate(DataSource dataSource) {
        String vendor = Dialects.detect(dataSource).name();
        return Flyway.configure()
                .dataSource(dataSource)
                .table(HISTORY_TABLE)
                .locations("classpath:db/platform/" + vendor)
                // 同库里还有别的模块的表与历史表：非空库照样允许从版本 0 开始建
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
