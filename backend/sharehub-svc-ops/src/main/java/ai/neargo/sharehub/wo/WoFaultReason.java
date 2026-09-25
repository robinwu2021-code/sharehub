package ai.neargo.sharehub.wo;

import java.util.Arrays;

/**
 * 故障原因分类（{@code wo_order.fault_reason_code} / {@code wo_handle.fault_reason_code}）。
 * 完工时必填（FAULT 类型）—— 没有它，「这类设备最常坏在哪」这个问题就只能翻备注去猜。
 */
public enum WoFaultReason {
    NETWORK, POWER, SLOT_MECH, LOCK, BATTERY, SCREEN, DAMAGE, OTHER;

    public static WoFaultReason of(String v) {
        return Arrays.stream(values()).filter(r -> r.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("故障原因非法: " + v
                        + "（仅 NETWORK/POWER/SLOT_MECH/LOCK/BATTERY/SCREEN/DAMAGE/OTHER）"));
    }
}
