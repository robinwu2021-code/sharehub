package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 提现单（stl_withdrawal，[db-design §5.5]）。
 *
 * <p><b>资金审批合规四件套</b>（[db-design §5.5] 「资金审批合规下界」）：
 * <ul>
 *   <li>{@link #fee} —— 手续费。口径来自 {@code sys_biz_rule(category='WITHDRAW')}，
 *       本域无该表访问权，走可注入的 {@code WithdrawFeePolicy} 策略接口取值；</li>
 *   <li>{@link #auditorNo} + {@link #auditorName} —— 审批人留痕，**服务端按会话回填**，不信前端传值；</li>
 *   <li>{@link #auditedAt} —— 审批时间（空 = 尚未审批，[db-design §1.5] 可空时间戳语义）；</li>
 *   <li>{@link #rejectReason} —— <b>驳回必须留原因</b>，缺失直接拒（见 {@code WithdrawalServiceImpl#audit}）。</li>
 * </ul>
 *
 * <p><b>派生列不落库</b>：「实际到账」= {@code amount - fee}，由展示层算，表里不存第三个金额列
 * —— 存了就会和两个源列打架。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stl_withdrawal")
public class StlWithdrawal extends BaseEntity {

    private String withdrawNo;

    /** 出款账户 {@code acct_account.account_no}。 */
    private String accountNo;

    /** VENUE / AGENT。 */
    private String payeeType;

    private String payeeNo;

    private String payeeName;

    private BigDecimal amount;

    /** 手续费，与 {@link #amount} 同币种。 */
    private BigDecimal fee;

    private String currency;

    /** 收款银行 {@code md_bank.bank_code}（自然键）。 */
    private String bankCode;

    /**
     * 收款账户引用 → {@code stl_payout_account.account_no}（B3）。审批通过时落定。
     *
     * <p>⚠️ 与 {@link #accountNo} 不是一回事：那个是<b>出款</b>账户（平台的钱从哪出），
     * 这个是<b>收款</b>方的账户（钱打到哪去）。两个名字太像，读代码时格外容易看反。
     */
    private String payoutAccountNo;

    /** 快照：审批那一刻的户名。账户日后改名，这笔提现要对得上当初的打款回单。 */
    private String payoutAccountName;

    /** 快照：审批那一刻的账号掩码。 */
    private String payoutAccountMasked;

    /** APPLY / AUDIT / PAYING / PAID / FAILED。 */
    private String status;

    private String appliedAt;

    /** 申请人（代理端/场地方本人发起，运营端无创建入口）。 */
    private String applicantNo;

    private String auditorNo;

    private String auditorName;

    private String auditedAt;

    private String rejectReason;

    private String paidAt;
}
