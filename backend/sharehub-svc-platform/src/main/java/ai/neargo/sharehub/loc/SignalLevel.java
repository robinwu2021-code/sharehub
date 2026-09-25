package ai.neargo.sharehub.loc;

import java.util.Arrays;

/** 勘测记录的现场信号强度（V107）。NONE 时勘测不能判通过 —— 没信号的柜子借不出也还不了。 */
public enum SignalLevel {
    STRONG, GOOD, WEAK, NONE;

    public static SignalLevel of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> ai.neargo.sharehub.common.BizException.badRequest("error.common.invalid_value", "signalLevel=" + v));
    }
}
