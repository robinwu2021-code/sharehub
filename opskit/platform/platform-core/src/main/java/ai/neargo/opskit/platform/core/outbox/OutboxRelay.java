package ai.neargo.opskit.platform.core.outbox;

import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.core.app.AppAppService;
import ai.neargo.opskit.platform.store.dao.OutboxDao;
import ai.neargo.opskit.platform.store.dao.WebhookDao;
import ai.neargo.opskit.platform.store.row.OutboxRow;
import ai.neargo.opskit.platform.store.row.SubscriptionRow;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 发件箱投递（21/02 §8.7），由 db-scheduler 的周期任务驱动（同一时刻只有一个实例在跑）。
 *
 * <p>HTTP 在事务外发；每条事件投完后用一个短事务记结果。某订阅已成功送达过的，重试时跳过 ——
 * 多个订阅方里只有一个失败时，不让成功的那个反复收到同一事件。
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 100;

    private final OutboxDao outbox;
    private final WebhookDao webhooks;
    private final AppAppService apps;
    private final WebhookDispatcher dispatcher;
    private final Backoff backoff;
    private final Clock clock;
    private final TransactionOperations tx;

    public OutboxRelay(OutboxDao outbox, WebhookDao webhooks, AppAppService apps, WebhookDispatcher dispatcher, Backoff backoff,
            Clock clock, TransactionOperations tx) {
        this.outbox = outbox;
        this.webhooks = webhooks;
        this.apps = apps;
        this.dispatcher = dispatcher;
        this.backoff = backoff;
        this.clock = clock;
        this.tx = tx;
    }

    /** @return 本轮处理的事件数 */
    public int relayOnce() {
        List<OutboxRow> due = outbox.due(AccountId.UNKNOWN, clock.millis(), BATCH);
        due.forEach(this::relay);
        return due.size();
    }

    private void relay(OutboxRow row) {
        AccountId account = new AccountId(row.accountId());
        Set<Long> alreadyOk = webhooks.succeededSubscriptions(account, row.id());
        List<SubscriptionRow> targets = webhooks.matching(account, row.workspaceId(), row.eventType()).stream()
                .filter(s -> !alreadyOk.contains(s.id())).toList();
        int attempt = row.attempts() + 1;
        String firstError = null;
        for (SubscriptionRow s : targets) {
            WebhookDispatcher.Result r = dispatcher.deliver(s.url(), row.payload(), apps.signingSecret(account, s.appId()), row.eventType(), row.idemKey());
            long now = clock.millis();
            tx.executeWithoutResult(t -> webhooks.recordDelivery(account, row.workspaceId(), row.id(), s.id(), attempt,
                    r.httpStatus(), r.latencyMs(), r.error(), now));
            if (!r.ok() && firstError == null) {
                firstError = r.httpStatus() != null ? "HTTP " + r.httpStatus() : r.error();
            }
        }
        long now = clock.millis();
        if (firstError == null) {
            tx.executeWithoutResult(t -> outbox.markDone(account, row.id(), now));
            return;
        }
        boolean exhausted = attempt >= backoff.maxAttempts();
        long nextAt = exhausted ? now : now + backoff.delayAfter(attempt).toMillis();
        String error = firstError;
        tx.executeWithoutResult(t -> outbox.markRetry(account, row.id(), attempt, nextAt, exhausted, error));
        if (exhausted) {
            // 谁该做什么：接入方修好回调后重投 —— POST /workspaces/{ws}/webhook-deliveries/{outboxId}/redeliver（integration:manage）
            log.error("webhook 投递重试耗尽，需接入方修复回调后调 POST /workspaces/{}/webhook-deliveries/{}/redeliver 重投 outboxId={} eventType={} idemKey={} lastError={}",
                    row.workspaceId(), row.id(),
                    row.id(), row.eventType(), row.idemKey(), error);
        } else {
            log.warn("webhook 投递失败，将自动重试 outboxId={} eventType={} attempt={} nextAtMs={} error={}",
                    row.id(), row.eventType(), attempt, nextAt, error);
        }
    }
}
