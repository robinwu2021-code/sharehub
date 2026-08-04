package ai.neargo.sharehub.cs;

/**
 * 报障分流依据的解析口 —— 把「这个问题该怎么处置」从代码里挪到字典里。
 *
 * <p><b>为什么是接口而不是 if-else</b>：[api/README §6A.2] 明确规定分流规则来自
 * {@code md_problem.suggested_action} 字典（运营端「问题管理」维护），
 * <b>不硬编码在代码里</b> —— 这样运营调整处置策略（比如「无法归还」从转工单改为直接退款）
 * 无需发版。硬编码一次，就等于把运营策略焊死在发布周期上。
 *
 * <p><b>真实来源</b>：{@code md_problem} 表归 platform/md 子域（另一分片实现）。
 * 本域只消费该字典，不持有它、不写它。待 md 分片提供 {@code ProblemService} 后，
 * 用一个读该表的实现替换 {@link StubProblemActionResolver} 即可，本域代码零改动。
 */
public interface ProblemActionResolver {

    /** 自助解决，直接关单。 */
    String SELF_SERVICE = "SELF_SERVICE";
    /** 开工单（{@code wo_order}，source=USER）。 */
    String TO_WORKORDER = "TO_WORKORDER";
    /** 建退款申请（{@code ord_refund}，待财务审批）。 */
    String TO_REFUND = "TO_REFUND";
    /** 转人工会话（{@code cs_session}）。 */
    String TO_CS = "TO_CS";

    /**
     * 查某个问题的建议处置动作。
     *
     * @param problemNo {@code md_problem.problem_no}（前缀 {@code ISS}）；空或查不到时
     *                  实现方应返回一个安全兜底值，绝不抛异常 —— 字典缺条目不该让用户报不了障
     * @return SELF_SERVICE / TO_WORKORDER / TO_REFUND / TO_CS 之一
     */
    String resolve(String problemNo);
}
