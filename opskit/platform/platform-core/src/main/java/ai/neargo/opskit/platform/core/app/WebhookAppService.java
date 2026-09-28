package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.dao.AppDao;
import ai.neargo.opskit.platform.store.dao.WebhookDao;
import ai.neargo.opskit.platform.store.row.SubscriptionRow;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionOperations;

/**
 * webhook 订阅（21/06 §六）。事件模式：精确类型（{@code approval.completed}）、前缀通配（{@code instance.*}）或 {@code *}。
 */
public class WebhookAppService {

    /** 一条订阅：事件模式 → 回调地址。 */
    public record Subscription(String eventType, String url) {
    }

    /** @param active 本次声明后启用的条数；disabled 本次停用（不在声明里）的条数 */
    public record Replaced(int active, int disabled) {
    }

    private static final Pattern EVENT = Pattern.compile("\\*|[a-z_]+(\\.[a-z_]+)*(\\.\\*)?");

    private final WebhookDao webhooks;
    private final AppDao apps;
    private final TransactionOperations tx;
    private final AuditTrail audit;

    public WebhookAppService(WebhookDao webhooks, AppDao apps, TransactionOperations tx, AuditTrail audit) {
        this.webhooks = webhooks;
        this.apps = apps;
        this.tx = tx;
        this.audit = audit;
    }

    /** 以声明为准整体替换某应用在某空间的订阅：声明里的启用，其余停用（不删，投递记录仍可追溯）。 */
    @SystemAction("运维面：部署方声明订阅")
    public Replaced replace(AccountId account, String workspaceId, String appId, List<Subscription> declared) {
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < declared.size(); i++) {
            Subscription s = declared.get(i);
            if (s.eventType() == null || s.eventType().length() > 64 || !EVENT.matcher(s.eventType()).matches()) {
                errors.add(new FieldError("subscriptions[" + i + "].eventType", "opskit.validation.invalid"));
            }
            if (!isHttpUrl(s.url())) {
                errors.add(new FieldError("subscriptions[" + i + "].url", "opskit.validation.invalid_url"));
            }
        }
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid webhook subscriptions", Map.of("appId", appId), errors);
        }
        return tx.execute(t -> {
            if (apps.find(account, appId).isEmpty()) {
                throw new OpsKitException(CommonErrors.NOT_FOUND, "app not found");
            }
            Set<String> keep = new LinkedHashSet<>();
            for (Subscription s : declared) {
                webhooks.subscribe(account, workspaceId, appId, s.eventType(), s.url());
                keep.add(s.eventType() + " " + s.url());
            }
            int disabled = 0;
            for (SubscriptionRow r : webhooks.ofApp(account, workspaceId, appId)) {
                if (r.enabled() && !keep.contains(r.eventType() + " " + r.url())) {
                    webhooks.disable(account, r.id());
                    disabled++;
                }
            }
            audit.record(account, workspaceId == null ? "" : workspaceId, AuditTrail.OPS, "webhook.subscriptions.replaced", "app:" + appId,
                    Map.of("active", keep.size(), "disabled", disabled));
            return new Replaced(keep.size(), disabled);
        });
    }

    private static boolean isHttpUrl(String s) {
        if (s == null || s.length() > 512) {
            return false;
        }
        try {
            URI u = URI.create(s);
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
