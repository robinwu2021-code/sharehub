package ai.neargo.sharehub.dev;

import java.util.Arrays;

/** 试借还状态（{@code dev_trial_rent.status}）。 */
public enum TrialRentStatus {
    /** 已下发弹出指令，等回执。 */
    EJECTING,
    /** 已弹出，等运维现场还回。 */
    WAIT_RETURN,
    PASSED,
    FAILED,
    /** 超 15 分钟未完成。 */
    EXPIRED;

    public static TrialRentStatus of(String v) {
        return Arrays.stream(values()).filter(a -> a.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("试借还状态非法: " + v));
    }

    public boolean inProgress() {
        return this == EJECTING || this == WAIT_RETURN;
    }
}
