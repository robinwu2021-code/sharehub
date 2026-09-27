package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.api.PlatformErrors;
import ai.neargo.opskit.platform.api.account.AccountView;
import ai.neargo.opskit.platform.api.account.AccountKind;
import ai.neargo.opskit.platform.api.account.CreateAccountRequest;
import ai.neargo.opskit.platform.api.account.CreateWorkspaceRequest;
import ai.neargo.opskit.platform.api.account.TenantStatus;
import ai.neargo.opskit.platform.api.account.WorkspaceRef;
import ai.neargo.opskit.platform.api.account.WorkspaceView;
import ai.neargo.opskit.platform.store.dao.AccountDao;
import ai.neargo.opskit.platform.store.dao.WorkspaceDao;
import ai.neargo.opskit.platform.store.row.AccountRow;
import ai.neargo.opskit.platform.store.row.WorkspaceRow;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 账户与空间（21/07 O1-1）。
 *
 * <p>这里的账户参数一律来自**已认证的调用方**（应用凭证所属账户 / 运维），不来自路径或请求体：
 * 用 A 账户的凭证去找 B 账户的空间，DAO 带着 A 的账户条件查，结果就是「不存在」。
 */
public class TenancyAppService {

    private static final Set<String> LOCALES = Set.of("zh", "en", "ar");
    private static final int MAX_NAME = 128;
    private static final int MAX_EXTERNAL_REF = 128;

    private final AccountDao accounts;
    private final WorkspaceDao workspaces;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final TransactionOperations tx;

    public TenancyAppService(AccountDao accounts, WorkspaceDao workspaces, BizNoGenerator ids, Clock clock, TransactionOperations tx) {
        this.accounts = accounts;
        this.workspaces = workspaces;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
    }

    @SystemAction("运维面：开通账户只由运维凭证调用")
    public AccountView createAccount(CreateAccountRequest req) {
        List<FieldError> errors = new ArrayList<>();
        requireName(req.name(), "name", errors);
        if (req.kind() == null) {
            errors.add(new FieldError("kind", "opskit.validation.required"));
        }
        throwIfAny(errors);
        long now = clock.millis();
        AccountId id = new AccountId(ids.nextPublicId("ac"));
        AccountRow row = new AccountRow(id.value(), req.name().strip(), req.kind().name(), "STANDARD", TenantStatus.ACTIVE.name(), now, now);
        tx.executeWithoutResult(s -> accounts.insert(id, row));
        return toView(row);
    }

    @SystemAction("应用凭证：伙伴开户时调用；账户由调用方认证给出，不经产品权限")
    public WorkspaceView createWorkspace(AccountId accountId, CreateWorkspaceRequest req) {
        List<FieldError> errors = new ArrayList<>();
        requireName(req.name(), "name", errors);
        String zone = req.defaultZone() == null || req.defaultZone().isBlank() ? "UTC" : req.defaultZone().strip();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            errors.add(new FieldError("defaultZone", "opskit.validation.invalid_zone"));
        }
        String locale = req.defaultLocale() == null || req.defaultLocale().isBlank() ? "zh" : req.defaultLocale().strip();
        if (!LOCALES.contains(locale)) {
            errors.add(new FieldError("defaultLocale", "opskit.validation.unsupported_locale"));
        }
        String ext = req.externalRef() == null || req.externalRef().isBlank() ? null : req.externalRef().strip();
        if (ext != null && ext.length() > MAX_EXTERNAL_REF) {
            errors.add(new FieldError("externalRef", "opskit.validation.too_long"));
        }
        throwIfAny(errors);

        return tx.execute(s -> {
            AccountRow account = accounts.find(accountId)
                    .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "account not found"));
            if (!TenantStatus.ACTIVE.name().equals(account.status())) {
                throw new OpsKitException(PlatformErrors.TENANT_INACTIVE, "account is " + account.status());
            }
            long now = clock.millis();
            WorkspaceRow row = new WorkspaceRow(accountId.value(), ids.nextPublicId("ws"), req.name().strip(), ext,
                    zone, locale, TenantStatus.ACTIVE.name(), now, now);
            if (!workspaces.insert(accountId, row)) {
                throw new OpsKitException(PlatformErrors.WORKSPACE_REF_TAKEN, "external ref already used in this account",
                        Map.of("externalRef", ext), List.of());
            }
            return toView(row);
        });
    }

    @SystemAction("寻址解析：调用方认证之后、进入任何用例之前执行")
    public WorkspaceView resolve(AccountId accountId, WorkspaceRef ref) {
        return (switch (ref) {
            case WorkspaceRef.ById byId -> workspaces.findById(accountId, byId.workspaceId());
            case WorkspaceRef.ByExternalRef ext -> workspaces.findByExternalRef(accountId, ext.externalRef());
        }).map(TenancyAppService::toView)
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found"));
    }

    @SystemAction("应用凭证 / 运维：列出本账户的空间")
    public List<WorkspaceView> listWorkspaces(AccountId accountId) {
        return workspaces.list(accountId).stream().map(TenancyAppService::toView).toList();
    }

    private static void requireName(String name, String path, List<FieldError> errors) {
        if (name == null || name.isBlank()) {
            errors.add(new FieldError(path, "opskit.validation.required"));
        } else if (name.strip().length() > MAX_NAME) {
            errors.add(new FieldError(path, "opskit.validation.too_long"));
        }
    }

    private static void throwIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "validation failed", Map.of(), errors);
        }
    }

    private static AccountView toView(AccountRow r) {
        return new AccountView(r.accountId(), r.name(), AccountKind.valueOf(r.kind()), r.plan(), TenantStatus.valueOf(r.status()), r.createdAtMs());
    }

    private static WorkspaceView toView(WorkspaceRow r) {
        return new WorkspaceView(r.accountId(), r.workspaceId(), r.name(), r.externalRef(), r.defaultZone(), r.defaultLocale(),
                TenantStatus.valueOf(r.status()), r.createdAtMs());
    }
}
