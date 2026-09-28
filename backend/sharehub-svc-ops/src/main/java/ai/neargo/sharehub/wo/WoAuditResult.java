package ai.neargo.sharehub.wo;

/**
 * 工单验收结论（{@code wo_order.audit_result}，与前端 {@code WoAuditResult} 同值域）。
 *
 * <h2>为什么这一列不能原样落库</h2>
 * 关单那段逻辑是**拿字面量比**出来的：{@code "FAIL".equals(auditResult)} 拒绝关单、
 * {@code "PASS"/"PASS_WITH_ISSUE"} 把 {@code closeReason} 推成 {@code RESOLVED}。
 * 而 {@code audit_result} 本身原先不校验 —— 传一个词表外的值（比如小写 {@code fail}）：
 * <ul>
 *   <li>「FAIL 不许关单」那道闸<b>拦不住它</b>；</li>
 *   <li>closeReason 推不出来，于是落到「关单原因必填」那条上被拒 ——
 *       报错说的是「原因没填」，而真正错的是验收结论那个词。</li>
 * </ul>
 * 两种后果都不是「值非法」这个真相。所以这里连大小写一起归一。
 */
public enum WoAuditResult {

    /** 验收通过。 */
    PASS,
    /** 通过但有遗留问题。 */
    PASS_WITH_ISSUE,
    /** 验收不合格 —— **不允许直接关单**，须走 /rework 退回返工。 */
    FAIL;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static WoAuditResult of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("验收结论必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("验收结论非法: " + v + "（仅 PASS/PASS_WITH_ISSUE/FAIL）");
        }
    }

    /** 没传就不校验：关单也可以只给 closeReason，不给验收结论。 */
    public static WoAuditResult ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
