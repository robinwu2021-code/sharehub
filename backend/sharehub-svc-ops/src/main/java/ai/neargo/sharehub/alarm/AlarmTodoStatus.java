package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 告警待办状态（{@code dev_alarm_todo.status}）。 */
public enum AlarmTodoStatus {
    OPEN, DONE, CANCELLED;

    public static AlarmTodoStatus of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AlarmTodoStatus 非法: " + v));
    }
}
