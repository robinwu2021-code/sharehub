package ai.neargo.sharehub.user.core.service;

import ai.neargo.sharehub.user.core.dto.UserCoreDtos.LogoffItem;

/**
 * 账号注销（usr_logoff，C-AC-05）—— PDPL 冷静期。
 *
 * <p>注销<b>不是立即删数据</b>：先落 {@code PENDING} 并给出 {@code coolingUntil}，
 * 期内用户可撤销（{@code CANCELLED}）；到期后由清除作业执行并回填 {@code purgedAt} + {@code DONE}。
 * 冷静期天数与「哪些表随注销物理清除、哪些匿名化保留用于财务留痕」待法务确认
 * （[db-design §十二 待确认 5]），本骨架先用默认值。
 */
public interface UserLogoffService {

    /** 当前注销申请；无则 {@code null}。 */
    LogoffItem current(String cUserNo);

    /**
     * 运营端注销队列（{@code GET /api/user/logoffs}）。
     *
     * <p>补这个口是因为**冷静期在运营端是看不见的**：C 端能提交、能自己撤销，
     * 而运营端零入口 —— 用户打电话说「我点错了」时，客服既看不到队列、也无从代为撤销，
     * 只能让他自己在 App 里找（而他正是因为找不到才打电话的）。
     *
     * <p>出参 {@link LogoffItem} 只有用户号与三个时间戳，**不含手机号与姓名** ——
     * 这也是它能给只读角色看的前提。
     */
    ai.neargo.common.core.PageResult<LogoffItem> pageForOps(Integer page, Integer size, String status);

    /**
     * 提交注销申请。
     *
     * @throws IllegalStateException 已有 PENDING 申请（同一用户至多一条，DDL 不设 UK 由此保证）
     */
    LogoffItem apply(String cUserNo);

    /**
     * 冷静期内撤销注销。
     *
     * @throws ai.neargo.sharehub.common.BizException 无 PENDING 申请 → 400（调用方的问题）；
     *         冷静期已过 → <b>409</b>（请求没毛病，是申请的状态不允许，此时数据可能已开始清除）。
     *         原注释写「只能用 IllegalArgumentException，因为本仓只把它映射成 400」——
     *         那条限制 2026-09-25 已经没有了（ServerException 现在按业务码推导状态）
     */
    LogoffItem cancel(String cUserNo);
}
