package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.finance.dto.FinDtos.LedgerEntry;
import ai.neargo.sharehub.finance.dto.FinDtos.LedgerPostReq;

import java.util.List;

/**
 * 账务分录服务（{@code acct_ledger}）—— <b>只有「查」和「记一组分录」两个动作</b>。
 *
 * <p><b>为什么没有 update / delete</b>：{@code acct_ledger} 是复式记账的只增（append）表
 * （[db-design §5.4]），分录一旦落库就是历史事实。记错了的唯一修法是**再记一组相反方向的红冲分录**，
 * 让错误和更正都留在流水里可追溯。给这里加一个 update 方法，等于让账本可以被悄悄改写 ——
 * 所以这不是「骨架先不写」，是永远不写。
 *
 * <p>{@link #post} 会在写入前校验借贷平衡（{@code SUM(DEBIT) == SUM(CREDIT)}），不平直接拒。
 */
public interface LedgerService {

    /** 分页查分录。{@code keyword} 匹配分录号/凭证号/订单号/科目名。 */
    PageResult<LedgerEntry> page(Integer page, Integer size, String keyword,
                                 String accountNo, String bizType);

    /** 按凭证号查一组分录（一张凭证的完整借贷两侧）。 */
    List<LedgerEntry> byVoucher(String voucherNo);

    /**
     * 记一组分录（一张凭证）。
     *
     * @return 生成的凭证号
     * @throws IllegalArgumentException 分录为空、方向非 DEBIT/CREDIT、金额非正、多币种混记，
     *                                  或**借贷不平衡**
     */
    String post(LedgerPostReq req);

    /**
     * 账户余额（按科目性质定方向）。
     *
     * <p>复式记账里余额方向随科目走：<b>资产/费用类 = Σ借 − Σ贷</b>，
     * <b>负债/权益/收入类 = Σ贷 − Σ借</b>。此前 {@code acct_account.balance} 是不带方向语义的单列，
     * 对负债科目（应付场地方/应付代理）取到的数会是反的 —— 应付越多余额越"负"，
     * 财务看到的是「我们欠人家 −4200」这种读不懂的数（未完成清单 B1 第三条）。
     *
     * <p>本方法由分录实时算，<b>不读 {@code acct_account.balance} 那一列</b> ——
     * 那一列当前无任何生产者（记分录不更新账户），信它等于信一个没人维护的数。
     *
     * @return 该科目方向上的余额；账户不存在抛 {@link IllegalArgumentException}
     */
    java.math.BigDecimal balanceOf(String accountNo);
}
