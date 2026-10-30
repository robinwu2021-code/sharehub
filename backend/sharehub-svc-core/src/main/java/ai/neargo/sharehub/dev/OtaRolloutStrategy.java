package ai.neargo.sharehub.dev;

import ai.neargo.sharehub.common.BizException;

/**
 * OTA 投放策略（{@code dev_ota_rollout.strategy}，DDL 注释 {@code 'GRAY 灰度/FULL 全量'}）。
 *
 * <p>与 {@link OtaRolloutScope} 是**两件事**：scope 说「打给哪些设备」，
 * strategy 说「一次打完还是分批打」。两列都写错过一个字母的后果不同 ——
 * 前者打错对象，后者把本该灰度的一次全量推了出去。
 */
public enum OtaRolloutStrategy {

    /** 灰度：分批推进，可中途暂停/回滚（DDL 默认值）。 */
    GRAY,
    /** 全量：一次推给范围内全部设备。 */
    FULL;

    /** 宽松解析：非法值抛 {@link BizException}（400，文案走 i18n key —— 中文字面量会原样送到英文 / 阿语界面）。 */
    public static OtaRolloutStrategy of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "strategy");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.common.invalid_value", v + " (GRAY/FULL)");
        }
    }
}
