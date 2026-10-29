package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.finance.entity.StlSelfBill;
import ai.neargo.sharehub.finance.entity.StlWithdrawal;

/**
 * 商家分成自开票（运营方原始需求 C10，V183）：{@code finance.share.self_billing=1} 时，
 * 每笔提现确认到账就由平台代商家出一张税务发票。只对有 VAT 税号的商家出。
 * <b>出单失败不影响到账登记</b> —— 记 ERROR，运营补齐后可重出。
 */
public interface SelfBillService {

    /** 提现到账后调用（单笔登记回执与付款任务批量确认都经这里）。 */
    void onWithdrawalPaid(StlWithdrawal w);

    PageResult<StlSelfBill> page(Integer page, Integer size, String agentNo, String keyword);

    StlSelfBill get(String billNo);

    /** 运营补出：开关当时关着、或商家后来才补 TRN、或出单异常时，对某笔已到账的提现补一张。 */
    StlSelfBill issueFor(String withdrawNo);
}
