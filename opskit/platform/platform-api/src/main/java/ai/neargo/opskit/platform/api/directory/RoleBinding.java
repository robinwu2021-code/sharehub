package ai.neargo.opskit.platform.api.directory;

/** @param principal {@code member:{memberId}} 或 {@code group:{code}} */
public record RoleBinding(String roleCode, String principal) {
}
