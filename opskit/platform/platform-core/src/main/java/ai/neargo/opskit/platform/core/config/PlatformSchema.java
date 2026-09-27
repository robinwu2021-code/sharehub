package ai.neargo.opskit.platform.core.config;

import ai.neargo.opskit.platform.store.PlatformMigrations;
import javax.sql.DataSource;

/**
 * 平台表的迁移。做成 Bean 是为了让它先于 db-scheduler 执行：调度器一启动就轮询它的执行表，
 * 表不在就刷错误日志。另外还要求开启 {@code db-scheduler.delay-startup-until-context-ready=true}（默认 false）。
 */
public final class PlatformSchema {

    private final int appliedMigrations;

    public PlatformSchema(DataSource dataSource) {
        this.appliedMigrations = PlatformMigrations.migrate(dataSource).migrationsExecuted;
    }

    public int appliedMigrations() {
        return appliedMigrations;
    }
}
