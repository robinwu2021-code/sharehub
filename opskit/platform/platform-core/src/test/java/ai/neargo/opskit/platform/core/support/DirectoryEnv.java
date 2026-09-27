package ai.neargo.opskit.platform.core.support;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.account.AccountKind;
import ai.neargo.opskit.platform.api.account.CreateAccountRequest;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.core.app.AppAppService;
import ai.neargo.opskit.platform.core.app.AuditTrail;
import ai.neargo.opskit.platform.store.dao.AuditDao;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import ai.neargo.opskit.platform.core.auth.ActorResolver;
import ai.neargo.opskit.platform.core.crypto.SecretCipherTest;
import ai.neargo.opskit.platform.store.dao.AccountDao;
import ai.neargo.opskit.platform.store.dao.AppDao;
import ai.neargo.opskit.platform.store.dao.GroupDao;
import ai.neargo.opskit.platform.store.dao.GroupMemberDao;
import ai.neargo.opskit.platform.store.dao.MemberDao;
import ai.neargo.opskit.platform.store.dao.RoleBindingDao;
import ai.neargo.opskit.platform.store.dao.RoleDao;
import ai.neargo.opskit.platform.store.dao.WorkspaceDao;
import java.util.Optional;
import java.util.Set;

/** 目录 / 认证链路测试的装配：一个伙伴账户 + 一个空间 + 全部平台服务。 */
public final class DirectoryEnv {

    public final PlatformFixture f;
    public final TenancyAppService tenancy;
    public final AppAppService apps;
    public final AuditTrail audit;
    public final DirectoryAppService directory;
    public final ActorResolver actors;
    public final AccountId account;
    public final WorkspaceId workspace;
    public final TenantContext tenant;

    public DirectoryEnv(TestDatabase db) {
        f = new PlatformFixture(db);
        WorkspaceDao ws = new WorkspaceDao(f.jdbc);
        tenancy = new TenancyAppService(new AccountDao(f.jdbc), ws, f.ids, f.clock, f.tx);
        audit = new AuditTrail(new AuditDao(f.jdbc, db.dialect()), f.clock);
        apps = new AppAppService(new AppDao(f.jdbc), ws, SecretCipherTest.cipher(), f.ids, f.clock, f.tx, audit);
        directory = new DirectoryAppService(new MemberDao(f.jdbc, db.dialect()), new GroupDao(f.jdbc, db.dialect()), new GroupMemberDao(f.jdbc, db.dialect()),
                new RoleDao(f.jdbc, db.dialect()), new RoleBindingDao(f.jdbc, db.dialect()), f.ids, f.clock, f.tx, audit);
        actors = new ActorResolver(tenancy, directory);
        account = new AccountId(tenancy.createAccount(new CreateAccountRequest("伙伴", AccountKind.PARTNER)).accountId());
        workspace = new WorkspaceId(tenancy.createWorkspace(account, new CreateWorkspaceRequest("空间", "T0001", null, null)).workspaceId());
        tenant = new TenantContext(account, workspace);
    }

    /** 以「应用自身」身份操作（账户级受信系统，免产品权限判定）。 */
    public Actor system() {
        return new Actor(tenant, Optional.empty(), Optional.of("app_test"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }
}
