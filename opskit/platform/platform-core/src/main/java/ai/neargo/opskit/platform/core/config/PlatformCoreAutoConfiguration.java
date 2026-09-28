package ai.neargo.opskit.platform.core.config;

import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.timer.TimerScheduler;
import ai.neargo.opskit.platform.core.app.AppAppService;
import ai.neargo.opskit.platform.core.app.AuditTrail;
import ai.neargo.opskit.platform.core.app.WebhookAppService;
import ai.neargo.opskit.platform.core.app.CalendarAppService;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import ai.neargo.opskit.platform.core.app.FederationAppService;
import ai.neargo.opskit.platform.core.app.MemberAttrAppService;
import ai.neargo.opskit.platform.core.auth.ActorResolver;
import ai.neargo.opskit.platform.core.auth.MemberTokenAuthenticator;
import ai.neargo.opskit.platform.store.dao.IdpDao;
import ai.neargo.opskit.platform.store.dao.MemberAttrDao;
import ai.neargo.opskit.platform.core.crypto.SecretCipher;
import ai.neargo.opskit.platform.core.idem.IdempotencyStore;
import ai.neargo.opskit.platform.core.outbox.Backoff;
import ai.neargo.opskit.platform.core.outbox.InProcessListener;
import ai.neargo.opskit.platform.core.outbox.OutboxRelay;
import ai.neargo.opskit.platform.core.outbox.OutboxWriter;
import ai.neargo.opskit.platform.core.outbox.WebhookDispatcher;
import ai.neargo.opskit.platform.core.timer.DbSchedulerTimerScheduler;
import ai.neargo.opskit.platform.store.PlatformStore;
import ai.neargo.opskit.platform.store.dao.AccountDao;
import ai.neargo.opskit.platform.store.dao.AppDao;
import ai.neargo.opskit.platform.store.dao.AuditDao;
import ai.neargo.opskit.platform.store.dao.CalendarDao;
import ai.neargo.opskit.platform.store.dao.GroupDao;
import ai.neargo.opskit.platform.store.dao.GroupMemberDao;
import ai.neargo.opskit.platform.store.dao.IdempotencyDao;
import ai.neargo.opskit.platform.store.dao.MemberDao;
import ai.neargo.opskit.platform.store.dao.OutboxDao;
import ai.neargo.opskit.platform.store.dao.RoleBindingDao;
import ai.neargo.opskit.platform.store.dao.RoleDao;
import ai.neargo.opskit.platform.store.dao.WebhookDao;
import ai.neargo.opskit.platform.store.dao.WorkspaceDao;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 平台层装配。排在数据源之后、db-scheduler 之前：先建平台表，再起调度器。
 *
 * <p>「在数据源之后」还顺带修了 db-scheduler 16.12.0 与 Boot 4 的不兼容：它的 starter 声明排在
 * {@code org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration} 之后 —— 那是 Boot 3 的类名，
 * Boot 4 已挪到 {@code org.springframework.boot.jdbc.autoconfigure}，排序失效，它的 {@code @ConditionalOnBean(DataSource)}
 * 判得太早，调度器被**静默跳过**。自动装配排序是拓扑排序、约束可传递，我们夹在中间即可把它排到数据源之后。
 * 守护用例：opskit-svc 的 OpsKitApplicationTest（去掉 afterName 即红，已实测）。
 */
@AutoConfiguration(
        afterName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"},
        before = DbSchedulerAutoConfiguration.class)
@EnableConfigurationProperties(PlatformProperties.class)
public class PlatformCoreAutoConfiguration {

    @Bean
    PlatformSchema platformSchema(DataSource dataSource) {
        return new PlatformSchema(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    Clock opskitClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    BizNoGenerator bizNoGenerator(Clock clock) {
        return new BizNoGenerator(clock);
    }

    /** 应用服务显式管事务（不用 @Transactional 代理）：内部互调不会悄悄丢事务，离开容器也能测。 */
    @Bean
    @ConditionalOnMissingBean(TransactionOperations.class)
    TransactionOperations opskitTransactions(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    @Bean
    SecretCipher secretCipher(PlatformProperties props) {
        if (props.crypto().key() == null || props.crypto().key().isBlank()) {
            throw new IllegalStateException("opskit.crypto.key 未配置：需要 32 字节密钥的 Base64（经环境变量 OPSKIT_CRYPTO_KEY 注入，不进仓库）");
        }
        return new SecretCipher(props.crypto().key());
    }

    @Bean
    TimerScheduler timerScheduler(ObjectProvider<Scheduler> scheduler) {
        return new DbSchedulerTimerScheduler(scheduler::getObject);
    }

    // ── DAO：由 store 的工厂创建，JDBC 类型不出 store（A5）────
    @Bean PlatformStore platformStore(DataSource ds) { return PlatformStore.create(ds); }
    @Bean AccountDao accountDao(PlatformStore s) { return s.accounts(); }
    @Bean WorkspaceDao workspaceDao(PlatformStore s) { return s.workspaces(); }
    @Bean AppDao appDao(PlatformStore s) { return s.apps(); }
    @Bean MemberDao memberDao(PlatformStore s) { return s.members(); }
    @Bean GroupDao groupDao(PlatformStore s) { return s.groups(); }
    @Bean GroupMemberDao groupMemberDao(PlatformStore s) { return s.groupMembers(); }
    @Bean RoleDao roleDao(PlatformStore s) { return s.roles(); }
    @Bean RoleBindingDao roleBindingDao(PlatformStore s) { return s.roleBindings(); }
    @Bean CalendarDao calendarDao(PlatformStore s) { return s.calendars(); }
    @Bean OutboxDao outboxDao(PlatformStore s) { return s.outbox(); }
    @Bean WebhookDao webhookDao(PlatformStore s) { return s.webhooks(); }
    @Bean IdempotencyDao idempotencyDao(PlatformStore s) { return s.idempotency(); }
    @Bean AuditDao auditDao(PlatformStore s) { return s.audit(); }
    @Bean IdpDao idpDao(PlatformStore s) { return s.idps(); }
    @Bean MemberAttrDao memberAttrDao(PlatformStore s) { return s.memberAttrs(); }

    // ── 应用服务 ───────────────────────────────────────────
    @Bean
    AuditTrail auditTrail(AuditDao dao, Clock clock) {
        return new AuditTrail(dao, clock);
    }

    @Bean
    TenancyAppService tenancyAppService(AccountDao a, WorkspaceDao w, BizNoGenerator ids, Clock clock, TransactionOperations tx) {
        return new TenancyAppService(a, w, ids, clock, tx);
    }

    @Bean
    AppAppService appAppService(AppDao a, WorkspaceDao w, SecretCipher cipher, BizNoGenerator ids, Clock clock, TransactionOperations tx, AuditTrail audit) {
        return new AppAppService(a, w, cipher, ids, clock, tx, audit);
    }

    @Bean
    WebhookAppService webhookAppService(WebhookDao w, AppDao a, TransactionOperations tx, AuditTrail audit) {
        return new WebhookAppService(w, a, tx, audit);
    }

    @Bean
    DirectoryAppService directoryAppService(MemberDao m, GroupDao g, GroupMemberDao gm, RoleDao r, RoleBindingDao b, BizNoGenerator ids,
            Clock clock, TransactionOperations tx, AuditTrail audit) {
        return new DirectoryAppService(m, g, gm, r, b, ids, clock, tx, audit);
    }

    /** 上层模块读目录的入口（21/05：只经客户端接口）。 */
    @Bean
    ai.neargo.opskit.platform.client.DirectoryClient directoryClient(DirectoryAppService directory) {
        return new ai.neargo.opskit.platform.core.embedded.EmbeddedDirectoryClient(directory);
    }

    @Bean
    ai.neargo.opskit.platform.client.WorkspaceClient workspaceClient(TenancyAppService tenancy) {
        return new ai.neargo.opskit.platform.core.embedded.EmbeddedWorkspaceClient(tenancy);
    }

    @Bean
    CalendarAppService calendarAppService(CalendarDao c, Clock clock, TransactionOperations tx, AuditTrail audit) {
        return new CalendarAppService(c, clock, tx, audit);
    }

    @Bean
    ActorResolver actorResolver(TenancyAppService tenancy, DirectoryAppService directory) {
        return new ActorResolver(tenancy, directory);
    }

    @Bean
    MemberAttrAppService memberAttrAppService(MemberAttrDao dao, Clock clock, TransactionOperations tx, AuditTrail audit) {
        return new MemberAttrAppService(dao, clock, tx, audit);
    }

    @Bean
    MemberTokenAuthenticator memberTokenAuthenticator(IdpDao idps, TenancyAppService tenancy, DirectoryAppService directory, ActorResolver actors,
            SecretCipher cipher, Clock clock, PlatformProperties props) {
        return new MemberTokenAuthenticator(idps, tenancy, directory, actors, cipher, clock, props.auth().tokenCacheMaxTtl());
    }

    @Bean
    FederationAppService federationAppService(IdpDao idps, SecretCipher cipher, Clock clock, TransactionOperations tx, AuditTrail audit,
            MemberTokenAuthenticator auth) {
        return new FederationAppService(idps, cipher, clock, tx, audit, auth::invalidateCaches);
    }

    @Bean
    IdempotencyStore idempotencyStore(IdempotencyDao dao, Clock clock, TransactionOperations tx, PlatformProperties props) {
        return new IdempotencyStore(dao, clock, tx, props.idempotency().retention());
    }

    // ── 事件 ──────────────────────────────────────────────
    @Bean
    EventPublisher eventPublisher(OutboxDao outbox, ObjectProvider<InProcessListener> listeners, BizNoGenerator ids, Clock clock, WorkspaceDao workspaces,
            MemberDao members) {
        return new OutboxWriter(outbox, listeners.orderedStream().toList(), ids, clock, workspaces, members);
    }

    @Bean
    OutboxRelay outboxRelay(OutboxDao o, WebhookDao w, AppAppService apps, Clock clock, TransactionOperations tx, PlatformProperties props) {
        List<java.time.Duration> steps = props.outbox().backoff();
        Backoff backoff = steps == null || steps.isEmpty() ? Backoff.DEFAULT : new Backoff(steps);
        return new OutboxRelay(o, w, apps, new WebhookDispatcher(clock), backoff, clock, tx);
    }

    // ── 周期任务（db-scheduler 保证同一时刻只有一个实例在跑）────
    @Bean
    Task<Void> outboxRelayTask(OutboxRelay relay, PlatformProperties props) {
        return Tasks.recurring("outbox-relay", FixedDelay.of(props.outbox().relayInterval())).execute((inst, ctx) -> relay.relayOnce());
    }

    @Bean
    Task<Void> idempotencyPurgeTask(IdempotencyStore store) {
        return Tasks.recurring("idempotency-purge", Schedules.daily(java.time.ZoneOffset.UTC, java.time.LocalTime.of(3, 30))).execute((inst, ctx) -> store.purgeExpired());
    }
}
