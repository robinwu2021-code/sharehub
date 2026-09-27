package ai.neargo.opskit.platform.core.timer;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.commons.timer.TimerScheduler;
import ai.neargo.opskit.platform.core.config.PlatformCoreAutoConfiguration;
import ai.neargo.opskit.platform.store.PlatformMigrations;
import com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 21/07 O0-6 验收，也是 21/05 §七「待核实」两项的实测：
 * ① 调度是否加入 Spring 事务 ② UTC 持久化配置（键名 {@code db-scheduler.always-persist-timestamp-in-utc}，默认 false）。
 */
class DbSchedulerTimerSchedulerTest {

    private static final String TASK = "test-timer";
    private static final WorkspaceId WS = new WorkspaceId("ws_test");

    /** 每个用例一个新的记录器，被触发的任务把自己的 ID 写进来。 */
    static final List<String> FIRED = new CopyOnWriteArrayList<>();

    private TimeZone original;

    /** 固定为非 UTC 时区，UTC 持久化的正反对照才在任何机器（包括时区就是 UTC 的 CI）上都有意义。 */
    @BeforeEach
    void useNonUtcJvmZone() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @AfterEach
    void restoreJvmZone() {
        TimeZone.setDefault(original);
    }

    private static ApplicationContextRunner runner(TestDatabase db) {
        FIRED.clear();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformCoreAutoConfiguration.class, DbSchedulerAutoConfiguration.class))
                .withBean(javax.sql.DataSource.class, db::dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(db.dataSource()))
                .withBean("testTimerTask", Task.class,
                        () -> Tasks.oneTime(TASK).execute((inst, ctx) -> FIRED.add(inst.getId())))
                .withPropertyValues(
                        "db-scheduler.table-name=plat_scheduled_tasks",
                        "opskit.crypto.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "db-scheduler.always-persist-timestamp-in-utc=true",
                        "db-scheduler.delay-startup-until-context-ready=true",
                        "db-scheduler.polling-interval=200ms",
                        "db-scheduler.immediate-execution-enabled=false");
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void migrationUsesItsOwnHistoryTable(TestDatabase db) {
        runner(db).run(ctx -> {
            JdbcClient jdbc = JdbcClient.create(db.dataSource());
            assertThat(count(jdbc, "SELECT COUNT(*) FROM plat_scheduled_tasks WHERE task_name = '" + TASK + "'")).isZero();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM " + PlatformMigrations.HISTORY_TABLE + " WHERE success = " + trueLiteral(db)))
                    .isGreaterThanOrEqualTo(1);
        });
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void schedulingJoinsTheCallersTransaction(TestDatabase db) {
        runner(db).run(ctx -> {
            TimerScheduler timers = ctx.getBean(TimerScheduler.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            Instant later = Instant.now().plus(1, ChronoUnit.HOURS);
            JdbcClient jdbc = JdbcClient.create(db.dataSource());

            tx.executeWithoutResult(s -> timers.schedule(TASK, new TimerId(WS, "committed", 1), later));
            tx.executeWithoutResult(s -> {
                timers.schedule(TASK, new TimerId(WS, "rolledback", 1), later);
                s.setRollbackOnly();
            });

            assertThat(instances(jdbc)).containsExactly("ws_test:committed:1");
        });
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void schedulingTheSameIdAgainMovesItsTime(TestDatabase db) {
        runner(db).run(ctx -> {
            TimerScheduler timers = ctx.getBean(TimerScheduler.class);
            TimerId id = new TimerId(WS, "k", 3);
            Instant first = Instant.parse("2030-01-01T00:00:00Z");
            Instant second = Instant.parse("2030-06-01T08:30:00Z");

            timers.schedule(TASK, id, first);
            timers.schedule(TASK, id, second);

            JdbcClient jdbc = JdbcClient.create(db.dataSource());
            assertThat(instances(jdbc)).containsExactly(id.encode());
            assertThat(storedExecutionTime(db, jdbc)).isEqualTo(second);
        });
    }

    /** 负对照：关掉 UTC 持久化，MySQL 系 / H2 的墙上时间就跟着 JVM 时区走 —— 证明上面那条靠的正是这个配置。 */
    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void withoutUtcPersistenceWallTimeFollowsTheJvmZone(TestDatabase db) {
        org.junit.jupiter.api.Assumptions.assumeFalse("postgresql".equals(db.name()), "timestamptz 自带时区，不受该配置影响");
        runner(db).withPropertyValues("db-scheduler.always-persist-timestamp-in-utc=false").run(ctx -> {
            Instant at = Instant.parse("2030-06-01T08:30:00Z");
            ctx.getBean(TimerScheduler.class).schedule(TASK, new TimerId(WS, "local", 1), at);
            assertThat(storedExecutionTime(db, JdbcClient.create(db.dataSource()))).isEqualTo(at.plus(8, ChronoUnit.HOURS));
        });
    }

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void dueTimerFiresWithItsEncodedId(TestDatabase db) {
        runner(db).run(ctx -> {
            ctx.getBean(TimerScheduler.class).schedule(TASK, new TimerId(WS, "PI01ABC", 7), Instant.now());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (FIRED.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(FIRED).containsExactly("ws_test:PI01ABC:7");
            assertThat(TimerId.decode(FIRED.get(0)).epoch()).isEqualTo(7);
        });
    }

    private static List<String> instances(JdbcClient jdbc) {
        return jdbc.sql("SELECT task_instance FROM plat_scheduled_tasks WHERE task_name = ? ORDER BY task_instance")
                .param(TASK).query(String.class).list();
    }

    /**
     * 还原库里存的执行时刻。PostgreSQL 是 timestamptz，驱动给的 Timestamp 本身就是正确时刻；
     * MySQL 系与 H2 的列不带时区，存的是墙上时间 —— 开启 UTC 持久化后它就是 UTC 墙上时间，按 UTC 解释才能还原原值。
     */
    private static Instant storedExecutionTime(TestDatabase db, JdbcClient jdbc) {
        // 表里还有平台自己的周期任务（投递、幂等清理），只看本测试的任务
        Object v = jdbc.sql("SELECT execution_time FROM plat_scheduled_tasks WHERE task_name = ?").param(TASK).query().singleValue();
        if (v instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        if ("postgresql".equals(db.name()) && v instanceof Timestamp ts) {
            return ts.toInstant();
        }
        LocalDateTime wall = v instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) v;
        return wall.toInstant(ZoneOffset.UTC);
    }

    private static int count(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static String trueLiteral(TestDatabase db) {
        return "postgresql".equals(db.name()) ? "true" : "1";
    }
}
