package ai.neargo.sharehub.alarm;

import java.util.Arrays;

/** 工单合并范围（合并键）：同设备 / 同站点 / 同区域当日。 */
public enum MergeScope {
    DEVICE, SITE, REGION;

    public static MergeScope of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MergeScope 非法: " + v));
    }
}
