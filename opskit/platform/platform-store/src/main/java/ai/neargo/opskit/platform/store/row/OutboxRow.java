package ai.neargo.opskit.platform.store.row;

public record OutboxRow(long id, String accountId, String workspaceId, String eventType, String idemKey, String payload,
        int attempts, long nextAtMs, Long doneAtMs, boolean failed, String lastError, long createdAtMs) {
}
