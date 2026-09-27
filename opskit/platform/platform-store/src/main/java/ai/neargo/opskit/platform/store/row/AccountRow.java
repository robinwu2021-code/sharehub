package ai.neargo.opskit.platform.store.row;

public record AccountRow(String accountId, String name, String kind, String plan, String status, long createdAtMs, long updatedAtMs) {
}
