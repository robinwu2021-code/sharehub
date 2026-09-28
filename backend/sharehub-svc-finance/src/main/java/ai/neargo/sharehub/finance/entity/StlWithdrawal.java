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

    /** 打款渠道 NEARPAY/MANUAL。V3 的 nearpay_payout_no 是死列，由本列 + payRef 取代（V57 注）。 */
    private String payChannel;

    /** 渠道流水号；与 payChannel 组成回执幂等键，重复登记会撞唯一键而不是悄悄写两遍。 */
    private String payRef;

    /** 登记回执的人。与 auditorNo 分开存，才查得出「审批人是否自己给自己放款」。 */
    private String payerNo;

    /** 快照：登记人姓名。 */
    private String payerName;

    /**
     * 审批人与放款人是否同一人（1=是）。**在放款那一刻定格**，不是每次比一下 ——
     * auditor_no / payer_no 将来可能被归档清理，而「当时是不是同一人」是个事实。
     * REQUIRE 模式下不会出现 1（直接拦住了）；WARN / OFF 才可能留下 1 ——
     * 关掉的是拦截，不是记录。
     */
    private Integer sameOperator;

    /** 打款失败原因。**不要与 rejectReason 合并** —— 那是审批驳回（钱从没打算出去）。 */
    private String failReason;

    // —— OpsKit 审批（M5，V143）。LOCAL 模式下三列都空 ——
    /** 审批在哪一步：{@link ai.neargo.sharehub.finance.WithdrawalApprovalState}；空 = 本地审批 / 已结束 */
    private String approvalState;
    /** OpsKit 审批单号（业务页「去审批」链接用）；审批结束后保留，便于追溯 */
    private String approvalNo;
    /** 送审轮次，从 1 起；「重新送审」加一（进 OpsKit 幂等键） */
    private Integer approvalRound;
    /** APPROVED_BLOCKED / SUBMIT_FAILED 时的原因，业务页照原文显示 */
    private String approvalNote;
}
