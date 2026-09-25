package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 处置方式：码即处置预案。 */
public enum AlarmDisposition {
    AUTO_FIX, WORK_ORDER, CS_CASE, TODO, NOTIFY;

    public static AlarmDisposition of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AlarmDisposition 非法: " + v));
    }
}
