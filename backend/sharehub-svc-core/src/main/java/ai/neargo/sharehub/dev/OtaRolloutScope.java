package ai.neargo.sharehub.dev;

import ai.neargo.sharehub.common.BizException;

/**
 * OTA 投放范围（{@code dev_ota_rollout.scope}，DDL 注释 {@code 'DEVICE/LOCATION/ALL'}）。
 *
 * <p>{@link #DEVICE} 与 {@link #LOCATION} 要配合 {@code target_ref} 读 ——
 * scope 写错的后果不是报错，是**这次投放打给了错的一批设备**，
 * 而固件是刷进硬件里的，发现时已经刷完了。
 */
public enum OtaRolloutScope {

    /** 指定设备：{@code target_ref} 是柜机编号。 */
    DEVICE,
    /** 指定点位：{@code target_ref} 是站点/点位编号。 */
    LOCATION,
    /** 全量（DDL 默认值）。 */
    ALL;

    /** 宽松解析：非法值抛 {@link BizException}（400，文案走 i18n key —— 中文字面量会原样送到英文 / 阿语界面）。 */
    public static OtaRolloutScope of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "scope");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.common.invalid_value", v + " (DEVICE/LOCATION/ALL)");
        }
    }
}
