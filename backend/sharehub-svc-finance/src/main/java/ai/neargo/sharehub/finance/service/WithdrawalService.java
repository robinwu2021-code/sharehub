package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.finance.dto.FinDtos.PayReceiptReq;
import ai.neargo.sharehub.finance.dto.FinDtos.WithdrawApplyReq;
import ai.neargo.sharehub.finance.dto.FinDtos.Withdrawal;

/**
 * 提现服务（{@code stl_withdrawal}）—— 资金审批合规四件套的落点。
 *
 * <p>创建入口只有一个：{@link #apply}，由**代理商/场地方本人**在代理端发起
 * （[api/README §六·A]「运营端没有也不该有创建入口」）。
 */
public interface WithdrawalService {

    /**
     * 提现申请。服务端负责三件前端不能碰的事：
     * <ol>
     *   <li>手续费由 {@code WithdrawFeePolicy} 现算（口径来自 {@code sys_biz_rule(WITHDRAW)}）；</li>
     *   <li>状态固定置 {@code APPLY}；</li>
     *   <li>申请人取自登录会话。</li>
     * </ol>
     */
    /** 提现审核队列（运营端列表）。原实现在 TradeController 读内存 SeedData，已迁来落库版。 */
    PageResult<Withdrawal> page(Integer page, Integer size, String keyword, String status);

    Withdrawal apply(WithdrawApplyReq req);

    /**
     * 提现审批。
     *
     * <p><b>驳回必须带原因</b>（[db-design §5.5] 合规下界）：{@code approve=false} 而
     * {@code rejectReason} 为空 → 抛 {@link IllegalArgumentException}，不允许「无理由驳回」落库。
     * <b>审批人不取前端传值</b>，一律由服务端按登录会话回填 {@code auditor_no}/{@code auditor_name}。
     *
     * <p>注：{@code POST /api/trade/withdrawals/{no}/audit} 的路由已由既有
     * {@code TradeController} 占用，本方法是给它（及后续迁移）用的落库实现。
     */
    Withdrawal audit(String withdrawNo, boolean approve, String rejectReason);

    /**
     * 打款回执登记：把「出款在途」的单子推到终态（必要功能清单 ⑮）。
     *
     * <p>此前这一步<b>整个是缺的</b>：状态机有 {@code PAY}/{@code FAIL} 迁移，
     * 但没有任何入口调用，审批完的单子永远停在 {@code PAYING}。
     *
     * <p>三条规则：
     * <ol>
     *   <li><b>成功必须有渠道流水号</b>——没有凭据的「已到账」在对账时无法证实；</li>
     *   <li><b>失败必须有原因</b>，与审批驳回分列（{@code fail_reason} ≠ {@code reject_reason}）；</li>
     *   <li><b>登记人服务端回填</b>，且与审批人分列存，事后查得出是不是同一个人。</li>
     * </ol>
     *
     * <p>重复登记同一笔渠道流水会撞唯一键 {@code uk_stl_withdrawal_payref}——
     * <b>宁可报错也不要静默写两遍</b>，两条「已付」记录在对账时没人分得清哪条是真的。
     */
    Withdrawal pay(String withdrawNo, PayReceiptReq req);
}
