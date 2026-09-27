package ai.neargo.opskit.platform.store.row;

public record WorkspaceRow(
        String accountId, String workspaceId, String name, String externalRef,
        String defaultZone, String defaultLocale, String status, long createdAtMs, long updatedAtMs) {
}
