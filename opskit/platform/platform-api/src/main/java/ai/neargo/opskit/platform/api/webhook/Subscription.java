package ai.neargo.opskit.platform.api.webhook;

/**
 * @param workspaceId 空 = 账户下全部空间
 * @param eventType   精确类型（{@code ticket.created}）、前缀通配（{@code ticket.*}）或 {@code *}
 */
public record Subscription(String workspaceId, String appId, String eventType, String url) {
}
