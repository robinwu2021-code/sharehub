package ai.neargo.sharehub.platform.md;

import ai.neargo.sharehub.common.BizException;

/**
 * 常见问题的建议处置（{@code md_problem.suggested_action}）—— **这条问题往哪儿走**。
 *
 * <p>它不是展示字段而是**路由依据**：C 端点进这条问题之后，是给他自助入口、
 * 转客服、走退款、还是开工单。写错的后果是**用户被送到错的地方**，
 * 而他不知道本该去别处 —— 比报错更难发现。
 *
 * <p>⚠️ {@code md_problem} 当前 0 行，这道校验是纯预防。
 */
public enum ProblemSuggestedAction {

    /** 给自助入口（用户自己就能解决）。 */
    SELF_SERVICE,
    /** 转人工客服。 */
    TO_CS,
    /** 走退款流程。 */
    TO_REFUND,
    /** 开工单（需要到现场或后台处理）。 */
    TO_WORKORDER;

    /** 宽松解析：非法值抛 400。文案走 i18n key。 */
    public static ProblemSuggestedAction of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.problem.action_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.problem.action_invalid", v);
        }
    }

    /** **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。 */
    public static ProblemSuggestedAction ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
