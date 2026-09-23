package ai.neargo.sharehub.agent.ext;

/**
 * 代理账号与自然人的状态（{@code agt_account.status} · {@code agt_principal.status}）。
 *
 * <p>两张表共用一套取值：账号是「人 × 主体」的成员关系，自然人是「人」本身，
 * 停用任一侧都让这条登录路径走不通，语义一致。
 *
 * <p>V47 迁移产生的占位自然人（{@code PR-LEGACY-*}）落的就是 {@link #DISABLED} ——
 * 掩码不可逆，存量账号的真实手机号推不回来，宁可让人工补资料后再启用，
 * 也不要产生一行「能登录但身份不明」的账号。
 */
public enum AccountStatus {

    ACTIVE,
    DISABLED;

    public static AccountStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("账号状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("账号状态非法: " + v + "（仅 ACTIVE/DISABLED）");
        }
    }
}
