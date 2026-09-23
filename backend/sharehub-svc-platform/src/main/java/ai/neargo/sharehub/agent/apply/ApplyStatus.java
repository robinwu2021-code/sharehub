package ai.neargo.sharehub.agent.apply;

import java.util.Set;

/**
 * 入驻申请状态（{@code agt_apply.status}，ADR-030 §三）。
 *
 * <p><b>自助注册与运营商代建共用同一个状态机</b> —— 这正是用户 2026-09-23 那句
 * 「条件相同」在代码里的样子：不按来源分叉。
 *
 * <pre>
 *   DRAFT ──▶ SUBMITTED ──▶ REVIEWING ──┬──▶ APPROVED
 *                    └──────────────────┴──▶ REJECTED ──（回填重提）──▶ SUBMITTED
 * </pre>
 *
 * <p>收进枚举而不是写裸字符串，理由见 {@code AlarmStatus}：那张表上出过一次无症状缺陷
 * （查 {@code 'ACK'} 而落库是 {@code ACKED}，已受理的告警永远开不出工单，不报错不留日志）。
 * 差一个字母编译器无从分辨；收进枚举之后，写错的那一刻就编译不过。
 */
public enum ApplyStatus {

    /** 暂存未提交。当前实现不产生这个状态，保留给「填一半退出」的前端草稿。 */
    DRAFT,
    /** 已提交，等待运营受理。 */
    SUBMITTED,
    /** 已受理，审核中。 */
    REVIEWING,
    /** 审核通过 —— 主体、自然人、属主账号已在同一事务里派生出来。 */
    APPROVED,
    /** 已驳回，{@code reject_reason} 原样回显给申请人，可回填重提。 */
    REJECTED;

    /**
     * 在途集合。
     *
     * <p>⚠️ <b>必须与 V48 生成列 {@code active_key} 的 CASE 分支保持一致</b>：
     * 那个生成列在途时取 {@code phone_hash}、终态时取 {@code apply_no}，
     * 「同一手机号至多一张在途申请」这条约束全靠它。两处改一处就会静默失配 ——
     * 表现是 DB 拦不住重复申请，或者反过来正常申请被误拦。
     */
    public static final Set<ApplyStatus> IN_FLIGHT = Set.of(DRAFT, SUBMITTED, REVIEWING);

    public boolean isInFlight() {
        return IN_FLIGHT.contains(this);
    }

    /** 可被审核的状态 —— 受理与否都能直接审。 */
    public boolean isAuditable() {
        return this == SUBMITTED || this == REVIEWING;
    }

    public static ApplyStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("申请状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "申请状态非法: " + v + "（仅 DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED）");
        }
    }
}
