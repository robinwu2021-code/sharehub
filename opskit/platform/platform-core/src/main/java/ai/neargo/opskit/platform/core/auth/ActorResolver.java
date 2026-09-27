package ai.neargo.opskit.platform.core.auth;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.api.account.WorkspaceView;
import ai.neargo.opskit.platform.api.app.AuthenticatedApp;
import ai.neargo.opskit.platform.api.directory.DirectorySource;
import ai.neargo.opskit.platform.api.directory.MemberUpsert;
import ai.neargo.opskit.platform.api.directory.MemberView;
import ai.neargo.opskit.platform.api.directory.OnBehalfOf;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 三类调用方 → {@link Actor}（21/02 §5.2）。空间授权在这里校验：应用访问未授权的空间 = 不存在。
 * 成员令牌的入口（{@code forMember}）随 O1-3 联邦登录接入，复用 {@link #memberActor}。
 */
public final class ActorResolver {

    private final TenancyAppService tenancy;
    private final DirectoryAppService directory;

    public ActorResolver(TenancyAppService tenancy, DirectoryAppService directory) {
        this.tenancy = tenancy;
        this.directory = directory;
    }

    /** 应用调用：不带 onBehalfOf = 应用自身；带 = 代成员（不存在即开通）。 */
    public Actor forApp(AuthenticatedApp app, WorkspaceRef ref, OnBehalfOf onBehalfOf) {
        AccountId account = new AccountId(app.accountId());
        WorkspaceView ws = tenancy.resolve(account, ref);
        if (!app.canAccess(ws.workspaceId())) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
        }
        TenantContext t = new TenantContext(account, new WorkspaceId(ws.workspaceId()));
        if (onBehalfOf == null) {
            return new Actor(t, Optional.empty(), Optional.of(app.appId()), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }
        MemberView m = directory.provision(t, new MemberUpsert(onBehalfOf.externalRef(), onBehalfOf.displayName(), null, null),
                DirectorySource.JIT);
        if (onBehalfOf.groups() != null) {
            directory.reconcileClaimedGroups(t, m.memberId(), onBehalfOf.groups());
        }
        return memberActor(t, m, Optional.of(app.appId()), Set.of(), onBehalfOf.perms(), onBehalfOf.scopes());
    }

    /**
     * 成员身份的 Actor：目录（组 · 角色绑定 · 成员标签）并上本次请求带来的声明。
     * 组的声明已在调用前落进目录（CLAIM 来源），所以这里只从目录读组。
     */
    public Actor memberActor(TenantContext t, MemberView m, Optional<String> viaApp, Set<String> claimedRoles,
            Set<String> externalPerms, Set<String> claimedScopes) {
        if (!"ACTIVE".equals(m.status())) {
            throw new OpsKitException(CommonErrors.UNAUTHENTICATED, "member disabled");
        }
        DirectoryAppService.Effective eff = directory.effective(t, m.memberId(), Set.of(), claimedRoles);
        Set<String> scopes = new TreeSet<>(m.scopes());
        scopes.addAll(claimedScopes);
        return new Actor(t, Optional.of(PrincipalRef.member(m.memberId())), viaApp, eff.groups(), eff.roles(), externalPerms,
                eff.permissions(), scopes);
    }
}
