package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 影响范围。 */
public enum ImpactScope {
    SITE, CABINET, SLOT, ORDER, ENTITY;

    public static ImpactScope of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ImpactScope 非法: " + v));
    }
}
