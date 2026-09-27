package ai.neargo.opskit.process.core.support;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.audit.AuditRecorder;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.process.store.ProcessMigrations;
import ai.neargo.opskit.process.store.ProcessStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 流程用例测试的公共装配：迁移好的流程表 + 真事务 + 固定时钟 + 记录调用的审计替身。不依赖平台模块。 */
public final class ProcessFixture {

    public record AuditCall(String workspaceId, String action, String target, Map<String, ?> detail) {
    }

    public final TestDatabase db;
    public final JdbcClient jdbc;
    public final TransactionTemplate tx;
    public final Clock clock = Clock.fixed(Instant.parse("2026-09-28T08:00:00Z"), ZoneOffset.UTC);
    public final ProcessStore store;
    public final List<AuditCall> audits = new CopyOnWriteArrayList<>();
    public final AuditRecorder audit = (actor, ws, action, target, detail) -> audits.add(new AuditCall(ws, action, target, detail));
    public final AccountId account = new AccountId("ac_test");
    public final WorkspaceId workspace = new WorkspaceId("ws_test");
    public final TenantContext tenant = new TenantContext(account, workspace);

    public ProcessFixture(TestDatabase db) {
        this.db = db;
        ProcessMigrations.migrate(db.dataSource());
        this.jdbc = JdbcClient.create(db.dataSource());
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        this.store = ProcessStore.create(db.dataSource());
    }

    /** 应用自身（受信系统，免产品权限判定）。 */
    public Actor system() {
        return new Actor(tenant, Optional.empty(), Optional.of("app_test"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    public Actor member(String memberId, Set<String> productPermissions) {
        return new Actor(tenant, Optional.of(PrincipalRef.member(memberId)), Optional.empty(), Set.of(), Set.of(), Set.of(), productPermissions, Set.of());
    }

    public Actor inOtherWorkspace() {
        return new Actor(new TenantContext(account, new WorkspaceId("ws_other")), Optional.empty(), Optional.of("app_test"),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }
}
