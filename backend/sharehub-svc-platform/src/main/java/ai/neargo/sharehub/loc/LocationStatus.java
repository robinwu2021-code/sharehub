package ai.neargo.sharehub.loc;

import java.util.Arrays;

/** 点位状态（{@code loc_location.status}，对齐清单 C2）。归档是独立维度（archived_at），不是状态值。 */
public enum LocationStatus {
    ACTIVE, PAUSED;

    public static LocationStatus of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> ai.neargo.sharehub.common.BizException.badRequest("error.location.status_invalid", v));
    }
}
