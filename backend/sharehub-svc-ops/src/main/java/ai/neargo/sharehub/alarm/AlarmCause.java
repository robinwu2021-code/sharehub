package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 根因（同一业务告警，根因不同派的活不同，见 dev_alarm_route）。 */
public enum AlarmCause {
    OFFLINE, UNSTABLE, NO_STOCK, FULL, FAULT, MIXED, NO_CONTRACT, EXPIRING, LOW_BATTERY, AGED, MISSING, SLA_BELOW, LOW_YIELD, CANCEL_FAILED, SN_SEEN, HAZARD, OVERHEAT;

    public static AlarmCause of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AlarmCause 非法: " + v));
    }
}
