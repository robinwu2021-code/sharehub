package ai.neargo.opskit.platform.api.account;

/**
 * @param externalRef   伙伴那边的租户号（可空；同账户内唯一），之后可用 {@code ext:{externalRef}} 寻址
 * @param defaultZone   IANA 时区，如 {@code Asia/Dubai}；空则 UTC
 * @param defaultLocale 如 {@code zh} / {@code en} / {@code ar}；空则 zh
 */
public record CreateWorkspaceRequest(String name, String externalRef, String defaultZone, String defaultLocale) {
}
