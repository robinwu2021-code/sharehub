package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 影响时段：高峰 / 营业 / 非营业。 */
public enum ImpactPeriod {
    PEAK, OPEN, CLOSED;

    public static ImpactPeriod of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ImpactPeriod 非法: " + v));
    }
}
