package ai.neargo.sharehub.wo;

import java.util.Arrays;

/**
 * 工单优先级（{@code wo_order.priority}，词表见列注释）。业务告警按「基准 + 影响加成」算出优先级后直接用它开单，
 * 所以词表归 wo 所有、alarm 引用之。
 */
public enum WoPriority {
    LOW, MEDIUM, HIGH, URGENT;

    public static WoPriority of(String v) {
        return Arrays.stream(values()).filter(p -> p.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工单优先级非法: " + v + "（仅 LOW/MEDIUM/HIGH/URGENT）"));
    }

    /** 加减档，封顶 URGENT、保底 LOW。 */
    public WoPriority plus(int delta) {
        return values()[Math.max(0, Math.min(values().length - 1, ordinal() + delta))];
    }

    public boolean higherThan(WoPriority o) {
        return o == null || ordinal() > o.ordinal();
    }
}
