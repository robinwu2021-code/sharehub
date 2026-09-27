package ai.neargo.opskit.svc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.timer.TimerScheduler;
import ai.neargo.opskit.platform.core.config.PlatformSchema;
import com.github.kagkarlsson.scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 冒烟：整个进程在 H2 上能起来，平台表已迁移，调度器在跑。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opskit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // 测试专用的全零密钥；生产密钥只从环境变量注入
        "opskit.crypto.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class OpsKitApplicationTest {

    @Autowired
    PlatformSchema platformSchema;

    @Autowired
    TimerScheduler timerScheduler;

    @Autowired
    Scheduler scheduler;

    @Autowired
    ai.neargo.opskit.platform.core.app.TenancyAppService tenancy;

    @Autowired
    ai.neargo.opskit.commons.event.EventPublisher events;

    @Test
    void platformServicesAreWired() {
        assertThat(tenancy.createAccount(new ai.neargo.opskit.platform.api.account.CreateAccountRequest("冒烟",
                ai.neargo.opskit.platform.api.account.AccountKind.DIRECT)).accountId()).startsWith("ac_");
        assertThat(events).isInstanceOf(ai.neargo.opskit.platform.core.outbox.OutboxWriter.class);
    }

    @Test
    void startsWithPlatformSchemaAndARunningScheduler() {
        assertThat(platformSchema.appliedMigrations()).isGreaterThanOrEqualTo(1);
        assertThat(timerScheduler).isNotNull();
        assertThat(scheduler.getSchedulerState().isStarted()).isTrue();
    }
}
