package ai.neargo.opskit.platform.store.row;

public record SubscriptionRow(long id, String accountId, String workspaceId, String appId, String eventType, String url, boolean enabled) {
}
