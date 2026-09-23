package ai.neargo.sharehub.agent;

/**
 * 运营主体状态（{@code agt_agent.status}，V1 DDL 注释 {@code ENABLED/SUSPENDED}）。
 *
 * <p>⚠️ <b>它只回答「能不能经营」，不回答「能不能拿钱」</b>（ADR-030 §3.6）。
 * 后者看收款账户是否完整。ai-shop 因为没分开，出现过「商家能卖、订单在来、结算单在生成，
 * 而收款号解析不到，账单留空钱欠着，商家一路上没收到任何提示」——
 * 他们的结论是结算侧的兜底「保证了不出错，没保证有人知道」。
 */
public enum AgentStatus {

    /** 在用。 */
    ENABLED,
    /** 停用 —— 不是删除，也不是归档（归档看 {@code archived_at}）。 */
    SUSPENDED;

    public static AgentStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("主体状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("主体状态非法: " + v + "（仅 ENABLED/SUSPENDED）");
        }
    }
}
