package ai.neargo.sharehub.platform.iam;

import ai.neargo.sharehub.common.BizException;

/**
 * 部门启用状态（{@code iam_dept.status}，DDL 注释 {@code 'ACTIVE/DISABLED'}）。
 *
 * <p>与 {@link EmployeeStatus} 的两档**不是一回事**：员工是 ACTIVE/LEFT（在职/离职），
 * 部门是启用/停用。两边都叫 status、都有 ACTIVE，所以别共用一个枚举 ——
 * 共用之后给任一边加一档，另一边会静默接受它。
 */
public enum DeptStatus {

    /** 启用（DDL 默认值）。 */
    ACTIVE,
    /** 停用：组织树上仍在，但不再作为可选上级/归属。 */
    DISABLED;

    /** 宽松解析：非法值抛 {@link BizException}（400，文案走 i18n key —— 中文字面量会原样送到英文 / 阿语界面）。 */
    public static DeptStatus of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "status");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.common.invalid_value", v + " (ACTIVE/DISABLED)");
        }
    }
}
