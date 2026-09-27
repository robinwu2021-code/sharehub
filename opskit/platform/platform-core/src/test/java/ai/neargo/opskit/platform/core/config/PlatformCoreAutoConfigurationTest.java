package ai.neargo.opskit.platform.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.testing.TestDatabases;
import ai.neargo.opskit.platform.core.app.FederationAppService;
import ai.neargo.opskit.platform.core.auth.MemberTokenAuthenticator;
import ai.neargo.opskit.platform.core.idem.IdempotencyStore;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class PlatformCoreAutoConfigurationTest {

    private final DataSource dataSource = TestDatabases.freshDatabase("h2").dataSource();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformCoreAutoConfiguration.class, DbSchedulerAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
            .withPropertyValues("db-scheduler.table-name=plat_scheduled_tasks", "db-scheduler.delay-startup-until-context-ready=true");

    @Test
    void refusesToStartWithoutTheCryptoKey() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("opskit.crypto.key");
        });
    }

    @Test
    void wiresThePlatformWhenConfigured() {
        runner.withPropertyValues("opskit.crypto.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(EventPublisher.class).hasSingleBean(IdempotencyStore.class)
                    .hasSingleBean(MemberTokenAuthenticator.class).hasSingleBean(FederationAppService.class).hasSingleBean(Scheduler.class);
        });
    }
}
