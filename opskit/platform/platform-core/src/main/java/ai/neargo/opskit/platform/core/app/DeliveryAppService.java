package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.commons.query.PageRequest;
import ai.neargo.opskit.commons.security.PermissionGuard;
import ai.neargo.opskit.commons.security.RequiresPermission;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.store.dao.OutboxDao;
import ai.neargo.opskit.platform.store.dao.WebhookDao;
import ai.neargo.opskit.platform.store.row.OutboxRow;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 事件投递的运维面（21/06 §六「耗尽标记失败、可重投」）：查空间内事件的投递状态与每次尝试，接入方修好回调后手工重投。
 * 需 integration:manage（应用自身不限）。
 */
public class DeliveryAppService {

    private static final Set<String> STATES = Set.of("FAILED", "PENDING", "DONE");

    /** @param state FAILED（重试耗尽）/ PENDING（重试中或待投）/ DONE */
    public record EventRow(long id, String eventType, String idemKey, String state, int attempts, Long nextAtMs, Long doneAtMs, String lastError,
            long createdAtMs) {
    }

    public record Detail(EventRow event, String payload, List<WebhookDao.DeliveryRow> attempts) {
    }

    private final OutboxDao outbox;
    private final WebhookDao webhooks;
    private final Clock clock;
    private final TransactionOperations tx;
    private final AuditTrail audit;

    public DeliveryAppService(OutboxDao outbox, WebhookDao webhooks, Clock clock, TransactionOperations tx, AuditTrail audit) {
        this.outbox = outbox;
        this.webhooks = webhooks;
        this.clock = clock;
        this.tx = tx;
        this.audit = audit;
    }

    @RequiresPermission("integration:manage")
    public Page<EventRow> query(Actor actor, String state, String eventType, PageRequest page) {
        PermissionGuard.require(actor, "integration:manage");
        if (state != null && !STATES.contains(state)) {
            throw new OpsKitException(CommonErrors.VALIDATION, "unknown state: " + state, Map.of(),
                    List.of(new ai.neargo.opskit.commons.error.FieldError("state", "opskit.query.unknown_value")));
        }
        long total = outbox.count(actor.tenant().accountId(), actor.tenant().workspaceId(), state, eventType);
        List<EventRow> rows = total == 0 ? List.of() : outbox.query(actor.tenant().accountId(), actor.tenant().workspaceId(), state, eventType,
                page.offset(), page.size()).stream().map(DeliveryAppService::row).toList();
        return Page.of(rows, total, page);
    }

    @RequiresPermission("integration:manage")
    public Detail get(Actor actor, long id) {
        PermissionGuard.require(actor, "integration:manage");
        OutboxRow r = own(actor, id);
        return new Detail(row(r), r.payload(), webhooks.deliveries(actor.tenant().accountId(), id));
    }

    /** 手工重投：清失败标记、立即到期，下一轮投递就发（再失败则重新计入失败）。已送达的不动 → 409。 */
    @RequiresPermission("integration:manage")
    public EventRow redeliver(Actor actor, long id) {
        PermissionGuard.require(actor, "integration:manage");
        return tx.execute(s -> {
            own(actor, id);
            if (outbox.redeliver(actor.tenant().accountId(), id, clock.millis()) != 1) {
                throw new OpsKitException(CommonErrors.STATE_CHANGED, "event already delivered");
            }
            audit.record(actor, "webhook.redelivered", "outbox:" + id, Map.of());
            return row(outbox.find(actor.tenant().accountId(), id).orElseThrow());
        });
    }

    // ── 账户级（运维面：跨空间）──────────────────────────────

    /** @param workspaceId 空 = 账户下全部空间 */
    @SystemAction("运维面：跨空间查投递")
    public Page<EventRow> queryAccount(AccountId account, String workspaceId, String state, String eventType, PageRequest page) {
        if (state != null && !STATES.contains(state)) {
            throw new OpsKitException(CommonErrors.VALIDATION, "unknown state: " + state, Map.of(),
                    List.of(new ai.neargo.opskit.commons.error.FieldError("state", "opskit.query.unknown_value")));
        }
        WorkspaceId ws = workspaceId == null || workspaceId.isBlank() ? null : new WorkspaceId(workspaceId);
        long total = outbox.count(account, ws, state, eventType);
        List<EventRow> rows = total == 0 ? List.of() : outbox.query(account, ws, state, eventType, page.offset(), page.size()).stream()
                .map(DeliveryAppService::row).toList();
        return Page.of(rows, total, page);
    }

    @SystemAction("运维面：跨空间重投")
    public EventRow redeliverAccount(AccountId account, long id, String operator) {
        return tx.execute(s -> {
            OutboxRow r = outbox.find(account, id).orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "event not found"));
            if (outbox.redeliver(account, id, clock.millis()) != 1) {
                throw new OpsKitException(CommonErrors.STATE_CHANGED, "event already delivered");
            }
            audit.record(account, r.workspaceId(), operator, "webhook.redelivered", "outbox:" + id, Map.of());
            return row(outbox.find(account, id).orElseThrow());
        });
    }

    private OutboxRow own(Actor actor, long id) {
        return outbox.find(actor.tenant().accountId(), id).filter(r -> r.workspaceId().equals(actor.tenant().workspaceId().value()))
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "event not found"));
    }

    private static EventRow row(OutboxRow r) {
        String state = r.doneAtMs() != null ? "DONE" : r.failed() ? "FAILED" : "PENDING";
        return new EventRow(r.id(), r.eventType(), r.idemKey(), state, r.attempts(), r.doneAtMs() == null && !r.failed() ? r.nextAtMs() : null,
                r.doneAtMs(), r.lastError(), r.createdAtMs());
    }
}
