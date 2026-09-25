package ai.neargo.sharehub.dev;

import java.util.Arrays;

/** 设备保护动作（{@code dev_protection.action}）。设备层止损，与「是不是业务告警」无关。 */
public enum ProtectionAction {
    /** 整柜停借（保还）。 */
    STOP_RENT,
    /** 仓位禁用：不借不还。 */
    SLOT_DISABLE,
    /** 仓位锁定：安全隔离（电池异常），宝不弹出。 */
    SLOT_LOCK,
    /** 降功率（过热）。 */
    DERATE;

    public static ProtectionAction of(String v) {
        return Arrays.stream(values()).filter(a -> a.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("保护动作非法: " + v + "（仅 STOP_RENT/SLOT_DISABLE/SLOT_LOCK/DERATE）"));
    }

    /** 作用于整柜（slot_index = -1）的动作。 */
    public boolean wholeCabinet() {
        return this == STOP_RENT || this == DERATE;
    }
}
