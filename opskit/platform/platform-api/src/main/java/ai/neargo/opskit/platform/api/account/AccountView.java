package ai.neargo.opskit.platform.api.account;

public record AccountView(String accountId, String name, AccountKind kind, String plan, TenantStatus status, long createdAtMs) {
}
