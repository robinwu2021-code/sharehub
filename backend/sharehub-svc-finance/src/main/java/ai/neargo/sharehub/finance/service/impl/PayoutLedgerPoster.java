package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.sharehub.finance.dto.FinDtos.LedgerLine;
import ai.neargo.sharehub.finance.dto.FinDtos.LedgerPostReq;
import ai.neargo.sharehub.finance.entity.AcctAccount;
import ai.neargo.sharehub.finance.entity.AcctLedger;
import ai.neargo.sharehub.finance.entity.StlSettlement;
import ai.neargo.sharehub.finance.entity.StlWithdrawal;
import ai.neargo.sharehub.finance.mapper.AcctAccountMapper;
import ai.neargo.sharehub.finance.mapper.AcctLedgerMapper;
import ai.neargo.sharehub.finance.service.LedgerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 应付商家的两笔自动记账（商家一期 B4 / G19）。
 *
 * <ul>
 *   <li><b>结算单确认</b>：借「分成成本」、贷「应付·收款方」—— 从这一刻起，这笔钱是平台欠商家的；</li>
 *   <li><b>提现单到账</b>：借「应付·收款方」、贷「平台银行存款」，有手续费时差额贷「手续费收入」——
 *       欠款冲减，钱真的出去了。</li>
 * </ul>
 *
 * <p><b>为什么挂在确认而不是分成生成</b>：分成明细在出账前还会被调整项改动，确认后金额才锁定
 * （需求方案 §10.1「结算单确认后金额锁定」）。在生成时记账的话，每次调整都得补一张红冲。
 *
 * <p><b>幂等</b>：按 {@code (biz_type, biz_no)} 判重，同一张结算单 / 提现单只记一次 ——
 * 重放确认、补登回执都不会把应付记成两倍。账本只增不改（{@link LedgerService}），记重了只能红冲，
 * 所以判重放在记账之前，而不是事后对账去发现。
 *
 * <p>与业务状态迁移<b>同一事务</b>：确认 / 到账成功而账没记上的状态不该存在 —— 那样「应付余额」
 * 与「已确认未付的结算单合计」就对不上了，而对不上正是财务最先会查的那个数。
 */
@Slf4j
@Component
public class PayoutLedgerPoster {

    public static final String BIZ_SETTLE_CONFIRM = "SETTLE_CONFIRM";
    public static final String BIZ_WITHDRAW_PAID = "WITHDRAW_PAID";

    private static final String PLATFORM = "PLATFORM";
    private static final String PLATFORM_NO = "MAIN";

    /**
     * 账户号按「主体 + 科目」确定性生成，而不是取号：并发首次记账时两个请求会生成同一个号，
     * 撞 {@code uk_account_no} 后重读即可 —— 取号的话会建出两个「应付·同一商家」账户，余额一分为二。
     */
    static final String ACC_SHARE_COST = "ACC-PLT-SHARE-COST";
    static final String ACC_BANK = "ACC-PLT-BANK";
    static final String ACC_FEE_INCOME = "ACC-PLT-WD-FEE";

    static String payableAccountNo(String payeeType, String payeeNo) {
        return "ACC-PAY-" + payeeType + "-" + payeeNo;
    }

    private final LedgerService ledger;
    private final AcctLedgerMapper entries;
    private final AcctAccountMapper accounts;

    public PayoutLedgerPoster(LedgerService ledger, AcctLedgerMapper entries, AcctAccountMapper accounts) {
        this.ledger = ledger;
        this.entries = entries;
        this.accounts = accounts;
    }

    /** 结算单确认 → 应付增加。金额 ≤ 0（调整项把它冲到零或负）不记：负的应付不是一笔账，是待追回，走调整流程。 */
    public void onSettlementConfirmed(StlSettlement s) {
        BigDecimal amount = s.getTotalAmount();
        if (amount == null || amount.signum() <= 0) {
            log.info("结算单金额非正，确认时不记应付 settleNo={} amount={}", s.getSettleNo(), amount);
            return;
        }
        if (posted(BIZ_SETTLE_CONFIRM, s.getSettleNo())) return;
        String payable = ensure(s.getPayeeType(), s.getPayeeNo(), "LIABILITY", s.getCurrency());
        String cost = ensure(PLATFORM, PLATFORM_NO, "EXPENSE", s.getCurrency(), ACC_SHARE_COST);
        ledger.post(new LedgerPostReq(null, null, BIZ_SETTLE_CONFIRM, s.getSettleNo(),
                "结算确认 " + s.getPeriod() + " " + s.getPayeeNo(), List.of(
                new LedgerLine(cost, cost, "DEBIT", amount, s.getCurrency()),
                new LedgerLine(payable, payable, "CREDIT", amount, s.getCurrency()))));
    }

    /** 提现到账 → 应付冲减。到账金额 = amount − fee；手续费是平台收入，不是少还了商家的钱。 */
    public void onWithdrawalPaid(StlWithdrawal w) {
        BigDecimal amount = w.getAmount();
        if (amount == null || amount.signum() <= 0) return;
        if (posted(BIZ_WITHDRAW_PAID, w.getWithdrawNo())) return;
        BigDecimal fee = w.getFee() == null ? BigDecimal.ZERO : w.getFee().max(BigDecimal.ZERO).min(amount);
        String payable = ensure(w.getPayeeType(), w.getPayeeNo(), "LIABILITY", w.getCurrency());
        String bank = ensure(PLATFORM, PLATFORM_NO, "ASSET", w.getCurrency(), ACC_BANK);

        List<LedgerLine> lines = new ArrayList<>();
        lines.add(new LedgerLine(payable, payable, "DEBIT", amount, w.getCurrency()));
        BigDecimal out = amount.subtract(fee);
        if (out.signum() > 0) lines.add(new LedgerLine(bank, bank, "CREDIT", out, w.getCurrency()));
        if (fee.signum() > 0) {
            String feeIncome = ensure(PLATFORM, PLATFORM_NO, "REVENUE", w.getCurrency(), ACC_FEE_INCOME);
            lines.add(new LedgerLine(feeIncome, feeIncome, "CREDIT", fee, w.getCurrency()));
        }
        ledger.post(new LedgerPostReq(null, null, BIZ_WITHDRAW_PAID, w.getWithdrawNo(),
                "提现到账 " + w.getPayeeNo() + (w.getPayRef() == null ? "" : " " + w.getPayRef()), lines));
    }

    private boolean posted(String bizType, String bizNo) {
        boolean dup = entries.selectCount(new LambdaQueryWrapper<AcctLedger>()
                .eq(AcctLedger::getBizType, bizType).eq(AcctLedger::getBizNo, bizNo)) > 0;
        if (dup) log.info("已记过账，跳过 bizType={} bizNo={}", bizType, bizNo);
        return dup;
    }

    private String ensure(String ownerType, String ownerNo, String acctType, String currency) {
        return ensure(ownerType, ownerNo, acctType, currency, payableAccountNo(ownerType, ownerNo));
    }

    private String ensure(String ownerType, String ownerNo, String acctType, String currency, String accountNo) {
        if (exists(accountNo)) return accountNo;
        AcctAccount a = new AcctAccount();
        a.setAccountNo(accountNo);
        a.setOwnerType(ownerType);
        a.setOwnerNo(ownerNo);
        a.setAcctType(acctType);
        a.setBalance(BigDecimal.ZERO);
        a.setCurrency(currency == null ? "AED" : currency);
        try {
            accounts.insert(a);
        } catch (DuplicateKeyException race) {
            // 并发首次记账：另一个请求刚建好同号账户，直接用它
        }
        return accountNo;
    }

    private boolean exists(String accountNo) {
        return accounts.selectCount(new LambdaQueryWrapper<AcctAccount>().eq(AcctAccount::getAccountNo, accountNo)) > 0;
    }
}
