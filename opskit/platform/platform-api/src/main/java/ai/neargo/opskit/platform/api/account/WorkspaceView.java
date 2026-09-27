package ai.neargo.opskit.platform.api.account;

public record WorkspaceView(
        String accountId, String workspaceId, String name, String externalRef,
        String defaultZone, String defaultLocale, TenantStatus status, long createdAtMs) {
}
