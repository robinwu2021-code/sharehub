package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 收款账户（{@code stl_payout_account}）—— 钱最终打到哪里。
 *
 * <p><b>运营主体与场地方共用这一张表</b>：两者都是分润受益方，都需要被打款。
 * 差别在收款机制的**另一侧** —— 运营主体还需要「商户」（进件、收单、子商户号），
 * 而场地方从不与消费者发生支付关系，它只是周期性地被打款。
 * 所以「收款账户」共用，「商户」只挂运营主体（ADR-029 §3.1）。
 *
 * <p><b>与提现单上那两列别混</b>：
 * {@code stl_withdrawal.account_no} 是<b>出款</b>账户（平台的钱从哪出），
 * {@code stl_withdrawal.bank_code} 只有收款银行、没有账号。本表补的是「打到哪个账号、户名是谁」。
 *
 * <p>业务键前缀 {@code PA}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stl_payout_account")
public class StlPayoutAccount extends BaseEntity {

    private String accountNo;

    /**
     * {@code AGENT} 代理商 / {@code VENUE} 场地方 —— 与 {@code stl_withdrawal.payee_type}
     * 和 {@code share_record.payee_type} 同一套取值（现网两张表存的都是 AGENT）。
     *
     * <p><b>不是 OPERATOR</b>，尽管 ADR-029 把代理商抽象成了「运营主体 Operator」：
     * 一张表用新词、另两张用旧词就 join 不上，也查不出「这笔打给了谁」。
     * 改名的窗口是 ADR-029 §5.1 的 B 步（随 ADR-021 的 S1），<b>那时三张表一起改</b>。
     * 判定见 {@code PayoutAccountServiceImpl.PAYEE_TYPES}。
     */
    private String payeeType;

    private String payeeNo;

    /** → {@code md_bank.bank_code}。 */
    private String bankCode;

    /** 户名。<b>须与主体法人名一致</b>，否则银行会把这笔打款退回来。 */
    private String accountName;

    /**
     * IBAN 掩码；明文入 {@code sharehub_pii}。
     *
     * <p>⚠️ <b>掩码不可做等值判断</b> —— 同一批号段的账号掩码可能相同。
     * 「这个账号是不是已经录过」要按明文或其哈希判，不能按这一列。
     */
    private String accountMasked;

    private String currency;

    /**
     * 是否该受益方的默认账户。
     *
     * <p><b>「恰好一个默认」由应用层保证，不是唯一键</b>：写成
     * {@code UNIQUE(payee_type, payee_no, is_default)} 会把「恰好一个非默认」也一起约束掉 ——
     * 一个受益方就只能有两个账户了。切换默认时在同一事务里把旧的置 0。
     */
    private Integer isDefault;

    /** ACTIVE / DISABLED。 */
    private String status;
}
