package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 恢复规则：条件消失即恢复（防抖后关闭）/ 处置完成才关闭 / 仅人工。 */
public enum RecoverRule {
    SIGNAL_CLEAR, DISPOSITION_DONE, NONE;

    public static RecoverRule of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("RecoverRule 非法: " + v));
    }
}
