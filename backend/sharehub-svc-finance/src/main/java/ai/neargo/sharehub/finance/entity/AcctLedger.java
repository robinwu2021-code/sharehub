package ai.neargo.sharehub.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 账务分录（acct_ledger，[db-design §5.4]）—— <b>复式记账、只增（append）表</b>。
 *
 * <p><b>为什么不继承 {@code BaseEntity}</b>：DDL 里本表没有 {@code version}/{@code deleted}
 * （[SKELETON_BRIEF §4]）。分录一旦落库就是历史事实，**不允许 update、不允许（软）删**；
 * 记错了只能再记一笔**相反方向的红冲分录**。因此本域的 service 只暴露「查询 + 记一组分录」，
 * 没有 update/delete 方法 —— 这不是漏写，是刻意的。
 *
 * <p><b>借贷平衡</b>：同一 {@code voucherNo}（凭证号）下的分录必须
 * {@code SUM(DEBIT) == SUM(CREDIT)}，由 {@code LedgerService#post} 在写入前校验，不平直接拒。
 */
@Data
@TableName("acct_ledger")
public class AcctLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** 分录号，前缀 {@code LE}（{@code BizKey.LEDGER_ENTRY}）。 */
    private String entryNo;

    /** 凭证号，前缀 {@code V}（{@code BizKey.VOUCHER}）。一张凭证 = 一组必须借贷平衡的分录。 */
    private String voucherNo;

    private String orderNo;

    private String accountNo;

    /** 科目名（现金 / 应付场地方 / 应付代理 / 平台收入…），写入时快照。 */
    private String account;

    /** DEBIT（借）/ CREDIT（贷）。 */
    private String direction;

    private BigDecimal amount;

    private String currency;

    /** 摘要。 */
    private String summary;

    /** 业务类型（RENT / SHARE / SETTLE / WITHDRAW / REFUND …）。 */
    private String bizType;

    /** 业务单号（与 bizType 组合定位来源单据）。 */
    private String bizNo;

    private String createdAt;

    /** 创建人（由 AuditMetaObjectHandler 自动填充；append 表无更新语义，故不设 updatedBy）。 */

    @TableField(fill = FieldFill.INSERT)

    private String createdBy;
}
