package ai.neargo.opskit.svc.bootstrap;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.api.account.AccountKind;
import ai.neargo.opskit.platform.api.account.CreateAccountRequest;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.api.account.WorkspaceView;
import ai.neargo.opskit.platform.api.directory.RoleUpsert;
import ai.neargo.opskit.platform.api.federation.ClaimMapping;
import ai.neargo.opskit.platform.api.federation.IdpConfig;
import ai.neargo.opskit.platform.api.federation.IdpKind;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.FederationAppService;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import ai.neargo.opskit.platform.core.app.AppAppService;
import ai.neargo.opskit.platform.core.app.WebhookAppService;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动引导：幂等地确保账户、空间、IdP、角色存在（每次启动都跑，已有的只更新 IdP 与角色，不重复建账户 / 空间）。
 * 配置缺关键项时拒绝启动 —— 起来了却登录不了，比起不来更难查。
 */
public class OpsKitBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OpsKitBootstrap.class);

    private final BootstrapProperties props;
    private final TenancyAppService tenancy;
    private final FederationAppService federation;
    private final DirectoryAppService directory;
    private final BootstrapDefinitions definitions;
    private final AppAppService apps;
    private final WebhookAppService webhooks;
    private final ai.neargo.opskit.platform.core.auth.OperatorAuth operators;
    private volatile BootstrapDefinitions.Summary lastDefinitions = BootstrapDefinitions.Summary.NONE;

    public OpsKitBootstrap(BootstrapProperties props, TenancyAppService tenancy, FederationAppService federation, DirectoryAppService directory,
            BootstrapDefinitions definitions, AppAppService apps, WebhookAppService webhooks, ai.neargo.opskit.platform.core.auth.OperatorAuth operators) {
        this.operators = operators;
        this.props = props;
        this.apps = apps;
        this.webhooks = webhooks;
        this.tenancy = tenancy;
        this.federation = federation;
        this.directory = directory;
        this.definitions = definitions;
    }

    /** @return 引导后的空间 */
    public WorkspaceView run() {
        if (props.idps().isEmpty() || props.idps().get(0).issuer() == null) {
            throw new IllegalStateException("opskit.bootstrap.idps 至少要登记一个 IdP（账户按它的 issuer 识别）");
        }
        if (props.workspace() == null || props.workspace().ref() == null || props.workspace().ref().isBlank()) {
            throw new IllegalStateException("opskit.bootstrap.workspace.ref 未配置（宿主的租户号，令牌 workspace 声明须与它一致）");
        }
        AccountId account = federation.accountOfIssuer(props.idps().get(0).issuer()).orElseGet(() -> {
            String name = props.accountName() == null ? props.workspace().ref() : props.accountName();
            AccountId created = new AccountId(tenancy.createAccount(new CreateAccountRequest(name, AccountKind.DIRECT)).accountId());
            log.info("启动引导建账户 account={}", created.value());
            return created;
        });
        WorkspaceView ws = workspace(account);
        for (BootstrapProperties.Idp i : props.idps()) {
            federation.upsert(account, idp(i));
        }
        TenantContext t = new TenantContext(account, new WorkspaceId(ws.workspaceId()));
        Actor system = new Actor(t, Optional.empty(), Optional.of("opskit-bootstrap"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        props.roles().forEach((code, role) -> directory.upsertRole(system, t.workspaceId(),
                new RoleUpsert(code, role.name().isEmpty() ? Map.of("en", code) : role.name(), new LinkedHashSet<>(role.permissions()))));
        Map<String, String> appActions = declareApps(account, ws.workspaceId());
        props.operators().forEach((code, o) -> {
            try {
                operators.register(code, o.credential(), account);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("opskit.bootstrap.operators." + code + " 不合法：" + e.getMessage(), e);
            }
        });
        BootstrapDefinitions.Summary defs = props.definitionsDir() == null || props.definitionsDir().isBlank()
                ? BootstrapDefinitions.Summary.NONE
                : definitions.publishAll(system, t.workspaceId(), java.nio.file.Path.of(props.definitionsDir()));
        log.info("启动引导完成 account={} workspace={} ref={} idps={} roles={} apps={} webhooks={} operators={} definitions={}", account.value(),
                ws.workspaceId(), ws.externalRef(), props.idps().size(), props.roles().keySet(), appActions, props.webhooks().size(),
                props.operators().keySet(), defs);
        lastDefinitions = defs;
        return ws;
    }

    /** 应用凭证与订阅。凭证本身永不进日志，只记动作（CREATED / ROTATED / UNCHANGED）。 */
    private Map<String, String> declareApps(AccountId account, String workspaceId) {
        Map<String, String> ids = new java.util.TreeMap<>();
        Map<String, String> actions = new java.util.TreeMap<>();
        props.apps().forEach((code, a) -> {
            AppAppService.Declared d;
            try {
                d = apps.ensureDeclared(account, code, a.credential(), a.signingSecret(), java.util.List.of(workspaceId));
            } catch (OpsKitException e) {
                throw new IllegalStateException("opskit.bootstrap.apps." + code + " 不合法：" + e.fieldErrors()
                        + "（凭证 okk_ / 签名密钥 oks_ 后接至少 43 / 32 位 Base64url，见部署说明的生成命令）", e);
            }
            ids.put(code, d.appId());
            actions.put(code, d.action());
        });
        Map<String, java.util.List<WebhookAppService.Subscription>> byApp = new java.util.TreeMap<>();
        for (BootstrapProperties.Webhook w : props.webhooks()) {
            if (!ids.containsKey(w.app())) {
                throw new IllegalStateException("opskit.bootstrap.webhooks 引用了 apps 段里没有的应用：" + w.app());
            }
            w.events().forEach(e -> byApp.computeIfAbsent(w.app(), k -> new java.util.ArrayList<>()).add(new WebhookAppService.Subscription(e, w.url())));
        }
        // 声明了应用却没声明订阅 = 该应用在本空间不订阅任何事件（整体替换为空）
        ids.forEach((code, appId) -> webhooks.replace(account, workspaceId, appId, byApp.getOrDefault(code, java.util.List.of())));
        return actions;
    }

    /** 最近一次引导发布定义的结果（测试与排障用）。 */
    public BootstrapDefinitions.Summary lastDefinitions() {
        return lastDefinitions;
    }

    private WorkspaceView workspace(AccountId account) {
        BootstrapProperties.Workspace w = props.workspace();
        try {
            return tenancy.resolve(account, new WorkspaceRef.ByExternalRef(w.ref()));
        } catch (OpsKitException notFound) {
            WorkspaceView created = tenancy.createWorkspace(account, new CreateWorkspaceRequest(w.name() == null ? w.ref() : w.name(), w.ref(),
                    w.zone() == null ? "UTC" : w.zone(), w.locale() == null ? "en" : w.locale()));
            log.info("启动引导建空间 workspace={} ref={}", created.workspaceId(), w.ref());
            return created;
        }
    }

    private static IdpConfig idp(BootstrapProperties.Idp i) {
        Map<String, String> c = i.claims() == null ? Map.of() : i.claims();
        return new IdpConfig(i.issuer(), IdpKind.valueOf(i.kind() == null ? "INTROSPECT" : i.kind()), i.jwksUrl(), i.audience(), i.introspectUrl(),
                i.clientId(), i.clientSecret(), i.tokenPrefix(),
                new ClaimMapping(c.getOrDefault("member", "sub"), c.get("displayName"), c.get("workspace"), c.get("groups"), c.get("roles"), c.get("perms"),
                        c.get("scopes")),
                i.jit() == null || i.jit());
    }
}
