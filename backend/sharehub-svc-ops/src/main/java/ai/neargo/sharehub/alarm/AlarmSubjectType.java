package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 告警主体（受影响的对象类型）。 */
public enum AlarmSubjectType {
    SITE, CABINET, SLOT, ORDER, USER, POWERBANK, CONTRACT, PAYEE, AGENT, WORK_ORDER, PAYMENT,
    /** 到仓待检单（V197）。 */
    INTAKE,
    /** 仓库（V204 低库存：subject_no = 仓号:设备类型）。 */
    WAREHOUSE;

    public static AlarmSubjectType of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AlarmSubjectType 非法: " + v));
    }
}
