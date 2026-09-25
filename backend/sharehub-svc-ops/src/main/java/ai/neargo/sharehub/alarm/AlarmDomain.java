package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 业务告警域（{@code dev_alarm_code.domain} / {@code dev_alarm.domain}）。按「谁受影响」分，不按设备部件分。 */
public enum AlarmDomain {
    AVAILABILITY, RETURNABILITY, TRANSACTION, SAFETY, ASSET, REVENUE, SERVICE, PARTNER, FUND;

    public static AlarmDomain of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AlarmDomain 非法: " + v));
    }
}
