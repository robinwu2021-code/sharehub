package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutAccount;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutAccountReq;

import java.util.Optional;

/**
 * 收款账户 —— 打款的最后一公里。
 *
 * <p>在它之前这条链是断的：提现审核页能点「通过」，审批完不知道往哪打钱。
 *
 * <p><b>「能经营」与「能拿钱」是两个判据，不要合用</b>（ADR-030 §3.6）：
 * {@code agt_agent.status = ENABLED} 只说明能铺设备、能产生分润明细；
 * 能不能提现要看这里有没有一个可用账户。ai-shop 因为没分开，出现过
 * 「商家能卖、订单在来、结算单在生成，而收款号解析不到，账单留空钱欠着，
 * 商家一路上没收到任何提示」—— 结算侧的兜底「保证了不出错，没保证有人知道」。
 */
public interface PayoutAccountService {

    PageResult<PayoutAccount> page(Integer page, Integer size, String payeeType, String payeeNo, String keyword);

    /**
     * 该受益方当前可用的默认账户；没有就是<b>还不能拿钱</b>。
     *
     * <p>代理门户的「你还不能收款」提示、提现申请的前置校验、审批时落快照，
     * 三处读的都是它 —— 判据只有一个出口，才不会出现「门户说能收、审批时又说不能」。
     */
    Optional<PayoutAccount> defaultOf(String payeeType, String payeeNo);

    /**
     * 新增 / 修改。
     *
     * <p>置为默认时，<b>同一事务</b>里把该受益方其它账户的 {@code isDefault} 清零 ——
     * 「恰好一个默认」是应用层保证的，不是唯一键（唯一键会把「恰好一个非默认」也约束掉）。
     */
    PayoutAccount save(PayoutAccountReq req);

    /** 停用。不做物理删除：历史提现单要能回溯到当时打给了哪条账户记录。 */
    PayoutAccount disable(String accountNo);
}
