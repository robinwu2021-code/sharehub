package ai.neargo.sharehub.platform.md;

import ai.neargo.sharehub.common.BizException;

/**
 * 常见问题分类（{@code md_problem.category}）—— C 端帮助中心的归类。
 *
 * <p>⚠️ {@code md_problem} 当前 0 行，这道校验是纯预防。
 * 分类错了的症状是**用户在帮助中心找不到那条问题**（他会去找客服，
 * 而这张表存在的目的正是让他不用找客服）—— 不报错。
 */
public enum ProblemCategory {

    /** 账号与登录。 */
    ACCOUNT,
    /** 计费与账单。 */
    BILLING,
    /** 设备（柜机/充电宝）。 */
    DEVICE,
    /** 借出。 */
    RENT,
    /** 归还。 */
    RETURN,
    /** 其它。 */
    OTHER;

    /** 宽松解析：非法值抛 400。文案走 i18n key。 */
    public static ProblemCategory of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.problem.category_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.problem.category_invalid", v);
        }
    }

    /** **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。 */
    public static ProblemCategory ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
