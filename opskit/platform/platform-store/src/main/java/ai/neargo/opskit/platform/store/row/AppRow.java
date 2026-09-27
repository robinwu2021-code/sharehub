package ai.neargo.opskit.platform.store.row;

public record AppRow(String accountId, String appId, String name, String credentialHash, String credentialHint,
        boolean allWorkspaces, String callbackBaseUrl, String signingSecretEnc, String status, long createdAtMs, long updatedAtMs) {
}
