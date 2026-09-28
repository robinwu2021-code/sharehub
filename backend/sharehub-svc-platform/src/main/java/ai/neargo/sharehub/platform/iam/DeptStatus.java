package ai.neargo.sharehub.platform.iam;

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

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static DeptStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("部门状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("部门状态非法: " + v + "（仅 ACTIVE/DISABLED）");
        }
    }
}
