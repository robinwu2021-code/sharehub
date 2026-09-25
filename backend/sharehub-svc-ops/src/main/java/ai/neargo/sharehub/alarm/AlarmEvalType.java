package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 判定方式：事件即成立 / 持续状态 / 窗口计数 / 周期指标。 */
public enum AlarmEvalType {
    EVENT, STATE, COUNT, METRIC;

    public static AlarmEvalType of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AlarmEvalType 非法: " + v));
    }
}
