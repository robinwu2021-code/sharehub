package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.jdbc.ConditionalUpdate;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.api.app.AppSecrets;
import ai.neargo.opskit.platform.api.app.AppView;
import ai.neargo.opskit.platform.api.app.AuthenticatedApp;
import ai.neargo.opskit.platform.api.app.CreateAppRequest;
import ai.neargo.opskit.platform.core.auth.Credentials;
import ai.neargo.opskit.platform.core.crypto.SecretCipher;
import ai.neargo.opskit.platform.store.dao.AppDao;
import ai.neargo.opskit.platform.store.dao.WorkspaceDao;
import ai.neargo.opskit.platform.store.row.AppRow;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;

/** 应用与凭证（21/07 O1-2）。M1 只开放给运维面；账户管理员自助管理随成员令牌（O1-3）一起开放。 */
public class AppAppService {

    private final AppDao apps;
    private final WorkspaceDao workspaces;
    private final SecretCipher cipher;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final TransactionOperations tx;
    private final AuditTrail audit;

    public AppAppService(AppDao apps, WorkspaceDao workspaces, SecretCipher cipher, BizNoGenerator ids, Clock clock, TransactionOperations tx,
            AuditTrail audit) {
        this.apps = apps;
        this.workspaces = workspaces;
        this.cipher = cipher;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
        this.audit = audit;
    }

    @SystemAction("运维面：签发应用凭证")
    public AppSecrets createApp(AccountId accountId, CreateAppRequest req) {
        List<FieldError> errors = new ArrayList<>();
        if (req.name() == null || req.name().isBlank()) {
            errors.add(new FieldError("name", "opskit.validation.required"));
        }
        if (req.callbackBaseUrl() != null && !isHttpUrl(req.callbackBaseUrl())) {
            errors.add(new FieldError("callbackBaseUrl", "opskit.validation.invalid_url"));
        }
        if (!req.allWorkspaces() && req.workspaceIds().isEmpty()) {
            errors.add(new FieldError("workspaceIds", "opskit.validation.required"));
        }
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "validation failed", Map.of(), errors);
        }
        String credential = Credentials.newCredential();
        String signingSecret = Credentials.newSigningSecret();
        long now = clock.millis();
        AppRow row = new AppRow(accountId.value(), ids.nextPublicId("app"), req.name().strip(), Credentials.hash(credential),
                Credentials.hint(credential), req.allWorkspaces(), req.callbackBaseUrl(), cipher.encrypt(signingSecret), "ACTIVE", now, now);

        return tx.execute(s -> {
            List<String> granted = req.allWorkspaces() ? List.of() : requireOwnWorkspaces(accountId, req.workspaceIds());
            apps.insert(accountId, row);
            apps.replaceWorkspaces(accountId, row.appId(), granted);
            audit.record(accountId, "", AuditTrail.OPS, "credential.issued", "app:" + row.appId(),
                    Map.of("allWorkspaces", req.allWorkspaces(), "workspaceIds", granted));
            return new AppSecrets(toView(row, granted), credential, signingSecret);
        });
    }

    /** @param action CREATED / ROTATED / UNCHANGED */
    public record Declared(String appId, String action) {
    }

    private static final java.util.regex.Pattern DECLARED_CREDENTIAL = java.util.regex.Pattern.compile("okk_[A-Za-z0-9_-]{43,96}");
    private static final java.util.regex.Pattern DECLARED_SECRET = java.util.regex.Pattern.compile("oks_[A-Za-z0-9_-]{32,96}");

    /**
     * 部署方声明的应用（启动引导的 apps 段）：凭证与签名密钥由部署方生成、经环境变量同时交给双方，服务端不产生明文。
     * 按名称幂等：不存在 → 建；凭证或签名密钥变了 → 换成声明的值（旧的立即失效）；都没变 → 不动。可访问空间按声明整体替换。
     */
    @SystemAction("运维面：部署方声明应用凭证")
    public Declared ensureDeclared(AccountId accountId, String name, String credential, String signingSecret, List<String> workspaceIds) {
        List<FieldError> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(new FieldError("name", "opskit.validation.required"));
        }
        if (credential == null || !DECLARED_CREDENTIAL.matcher(credential).matches()) {
            errors.add(new FieldError("credential", "opskit.validation.invalid"));
        }
        if (signingSecret == null || !DECLARED_SECRET.matcher(signingSecret).matches()) {
            errors.add(new FieldError("signingSecret", "opskit.validation.invalid"));
        }
        if (workspaceIds == null || workspaceIds.isEmpty()) {
            errors.add(new FieldError("workspaceIds", "opskit.validation.required"));
        }
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid declared app", Map.of("name", String.valueOf(name)), errors);
        }
        String hash = Credentials.hash(credential);
        return tx.execute(s -> {
            List<String> granted = requireOwnWorkspaces(accountId, workspaceIds);
            apps.findByCredentialHash(AccountId.UNKNOWN, hash).filter(o -> !o.accountId().equals(accountId.value()) || !o.name().equals(name))
                    .ifPresent(o -> {
                        throw new OpsKitException(CommonErrors.VALIDATION, "credential already belongs to another app", Map.of("name", name),
                                List.of(new FieldError("credential", "opskit.validation.duplicate")));
                    });
            long now = clock.millis();
            Optional<AppRow> current = apps.findByName(accountId, name.strip());
            if (current.isEmpty()) {
                AppRow row = new AppRow(accountId.value(), ids.nextPublicId("app"), name.strip(), hash, Credentials.hint(credential), false, null,
                        cipher.encrypt(signingSecret), "ACTIVE", now, now);
                apps.insert(accountId, row);
                apps.replaceWorkspaces(accountId, row.appId(), granted);
                audit.record(accountId, "", AuditTrail.OPS, "credential.declared", "app:" + row.appId(), Map.of("workspaceIds", granted));
                return new Declared(row.appId(), "CREATED");
            }
            AppRow app = current.get();
            boolean changed = !app.credentialHash().equals(hash) || !cipher.decrypt(app.signingSecretEnc()).equals(signingSecret);
            if (changed) {
                ConditionalUpdate.requireExactlyOne(apps.rotateCredential(accountId, app.appId(), app.credentialHash(), hash, Credentials.hint(credential),
                        cipher.encrypt(signingSecret), now), "app credential");
                audit.record(accountId, "", AuditTrail.OPS, "credential.rotated", "app:" + app.appId(),
                        Map.of("oldHint", app.credentialHint(), "newHint", Credentials.hint(credential), "by", "bootstrap"));
            }
            if (!apps.workspaces(accountId, app.appId()).equals(granted)) {
                apps.replaceWorkspaces(accountId, app.appId(), granted);
            }
            return new Declared(app.appId(), changed ? "ROTATED" : "UNCHANGED");
        });
    }

    /** 立即失效旧凭证与旧签名密钥（M1 不做宽限期；需要平滑切换时由接入方先改配置再轮换）。 */
    @SystemAction("运维面：轮换凭证")
    public AppSecrets rotate(AccountId accountId, String appId) {
        return tx.execute(s -> {
            AppRow current = apps.find(accountId, appId)
                    .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "app not found"));
            String credential = Credentials.newCredential();
            String signingSecret = Credentials.newSigningSecret();
            ConditionalUpdate.requireExactlyOne(apps.rotateCredential(accountId, appId, current.credentialHash(),
                    Credentials.hash(credential), Credentials.hint(credential), cipher.encrypt(signingSecret), clock.millis()), "app credential");
            AppRow rotated = apps.find(accountId, appId).orElseThrow();
            // 只记提示位，永远不记凭证本身
            audit.record(accountId, "", AuditTrail.OPS, "credential.rotated", "app:" + appId,
                    Map.of("oldHint", current.credentialHint(), "newHint", rotated.credentialHint()));
            return new AppSecrets(toView(rotated, apps.workspaces(accountId, appId)), credential, signingSecret);
        });
    }

    /**
     * 认证入口。任何失败（缺、格式错、不存在、已停用）一律 401 且信息相同，不给探测者区分线索。
     */
    @SystemAction("认证：先于一切用例执行")
    public AuthenticatedApp authenticate(String bearer) {
        if (bearer == null || !bearer.startsWith("okk_") || bearer.length() > 100) {
            throw unauthenticated();
        }
        AppRow row = apps.findByCredentialHash(AccountId.UNKNOWN, Credentials.hash(bearer)).orElseThrow(AppAppService::unauthenticated);
        if (!"ACTIVE".equals(row.status())) {
            throw unauthenticated();
        }
        AccountId account = new AccountId(row.accountId());
        return new AuthenticatedApp(row.accountId(), row.appId(), row.allWorkspaces(),
                row.allWorkspaces() ? java.util.Set.of() : new HashSet<>(apps.workspaces(account, row.appId())));
    }

    /** 给 webhook 投递用：取回签名密钥原文。 */
    @SystemAction("事件投递：签名")
    public String signingSecret(AccountId accountId, String appId) {
        return apps.find(accountId, appId).map(r -> cipher.decrypt(r.signingSecretEnc()))
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "app not found"));
    }

    private List<String> requireOwnWorkspaces(AccountId accountId, List<String> ids) {
        List<String> missing = ids.stream().filter(ws -> workspaces.findById(accountId, ws).isEmpty()).toList();
        if (!missing.isEmpty()) {
            // 别的账户的空间与不存在的空间同样报「不存在」
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found", Map.of("workspaceIds", missing), List.of());
        }
        return ids.stream().distinct().sorted().toList();
    }

    private static boolean isHttpUrl(String s) {
        try {
            URI u = URI.create(s);
            return ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) && u.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static OpsKitException unauthenticated() {
        return new OpsKitException(CommonErrors.UNAUTHENTICATED, "invalid credential");
    }

    private static AppView toView(AppRow r, List<String> workspaceIds) {
        return new AppView(r.accountId(), r.appId(), r.name(), r.allWorkspaces(), workspaceIds, r.callbackBaseUrl(),
                r.credentialHint(), r.status(), r.createdAtMs());
    }
}
