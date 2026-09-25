package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 告警主体（受影响的对象类型）。 */
public enum AlarmSubjectType {
    SITE, CABINET, SLOT, ORDER, USER, POWERBANK, CONTRACT, PAYEE, AGENT, WORK_ORDER, PAYMENT;

    public static AlarmSubjectType of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AlarmSubjectType 非法: " + v));
    }
}
