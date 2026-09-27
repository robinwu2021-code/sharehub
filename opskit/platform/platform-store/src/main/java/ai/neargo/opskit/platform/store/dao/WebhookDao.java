package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.row.SubscriptionRow;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 订阅与投递记录。 */
public final class WebhookDao {

    private static final RowMapper<SubscriptionRow> MAPPER = (rs, i) -> new SubscriptionRow(
            rs.getLong("id"), rs.getString("account_id"), rs.getString("workspace_id"), rs.getString("app_id"),
            rs.getString("event_type"), rs.getString("url"), rs.getInt("enabled") == 1);

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public WebhookDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    public void subscribe(AccountId account, String workspaceId, String appId, String eventType, String url) {
        jdbc.sql(dialect.upsert("plat_webhook_subscription", List.of("account_id", "workspace_id", "app_id", "event_type", "url"),
                        List.of("account_id", "workspace_id", "app_id", "event_type", "url", "enabled"), List.of("enabled")))
                .params(account.value(), workspaceId == null ? "" : workspaceId, appId, eventType, url, 1).update();
    }

    /** 与该空间、该事件类型匹配的启用订阅。通配的匹配在内存里做（订阅量小）。 */
    public List<SubscriptionRow> matching(AccountId account, String workspaceId, String eventType) {
        return jdbc.sql("SELECT * FROM plat_webhook_subscription WHERE account_id = ? AND workspace_id IN ('', ?) AND enabled = 1 ORDER BY id")
                .params(account.value(), workspaceId).query(MAPPER).list().stream()
                .filter(s -> matches(s.eventType(), eventType)).toList();
    }

    static boolean matches(String pattern, String eventType) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith(".*")) {
            return eventType.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(eventType);
    }

    public void recordDelivery(AccountId account, String workspaceId, long outboxId, long subscriptionId, int attempt,
            Integer httpStatus, Integer latencyMs, String error, long now) {
        jdbc.sql("INSERT INTO plat_webhook_delivery (account_id, workspace_id, outbox_id, subscription_id, attempt, http_status, latency_ms, error, at_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(account.value(), workspaceId, outboxId, subscriptionId, attempt, httpStatus, latencyMs, error, now).update();
    }

    /** 这个事件已经成功送达过的订阅 —— 重试时跳过它们。 */
    public Set<Long> succeededSubscriptions(AccountId account, long outboxId) {
        return Set.copyOf(jdbc.sql("SELECT DISTINCT subscription_id FROM plat_webhook_delivery "
                        + "WHERE account_id = ? AND outbox_id = ? AND http_status BETWEEN 200 AND 299")
                .params(account.value(), outboxId).query(Long.class).list());
    }

    public int deliveryCount(AccountId account, long outboxId) {
        return jdbc.sql("SELECT COUNT(*) FROM plat_webhook_delivery WHERE account_id = ? AND outbox_id = ?")
                .params(account.value(), outboxId).query(Integer.class).single();
    }
}
