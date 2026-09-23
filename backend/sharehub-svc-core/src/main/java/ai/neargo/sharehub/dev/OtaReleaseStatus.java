package ai.neargo.sharehub.dev;

/**
 * OTA 固件版本状态（{@code dev_ota_release.status}，DDL 注释
 * {@code 'DRAFT/PUBLISHED/PAUSED/COMPLETED/ARCHIVED'}）。
 *
 * <p>与 {@link OtaRolloutStatus} 是**两件事**：版本说的是"这个固件包处于什么阶段"，
 * 投放说的是"这一次推送做到哪了"。一个 {@code PUBLISHED} 的版本可以有多次投放。
 */
public enum OtaReleaseStatus {

    /** 草稿（DDL 默认值）。 */
    DRAFT,
    /** 已发布，可用于投放。 */
    PUBLISHED,
    /** 已暂停发布。 */
    PAUSED,
    /** 已完成（全量覆盖）。 */
    COMPLETED,
    /** 已归档。 */
    ARCHIVED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static OtaReleaseStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("固件版本状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("固件版本状态非法: " + v
                    + "（仅 DRAFT/PUBLISHED/PAUSED/COMPLETED/ARCHIVED）");
        }
    }
}
