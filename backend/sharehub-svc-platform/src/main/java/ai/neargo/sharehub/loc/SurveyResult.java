package ai.neargo.sharehub.loc;

import java.util.Arrays;

/** 现场勘测结论（V107）。以站点最近一次勘测为准；首台设备上线须最近一次为 PASS。 */
public enum SurveyResult {
    PASS, FAIL;

    public static SurveyResult of(String v) {
        return Arrays.stream(values()).filter(x -> x.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> ai.neargo.sharehub.common.BizException.badRequest("error.common.invalid_value", "result=" + v));
    }
}
