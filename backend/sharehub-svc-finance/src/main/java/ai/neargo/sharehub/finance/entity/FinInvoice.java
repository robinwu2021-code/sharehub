package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 发票（fin_invoice，[db-design §5.5]）—— <b>运营侧开票管理</b>。
 *
 * <p><b>业务键前缀是 {@code INV}</b>（{@code BizKey.INVOICE_OPS}）。C 端「开票申请」是另一张表、
 * 前缀 {@code UINV}（{@code BizKey.INVOICE_USER}）—— 两者混用会让两表业务键直接撞号，
 * 见 {@code BizKey} 的相邻前缀告警。
 *
 * <p><b>不落 {@code order_nos JSON}</b>（[db-design §1.7]）：关联订单一律走
 * {@link FinInvoiceItem} 子表，否则无法按订单号检索、也约束不了合法值。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_invoice")
public class FinInvoice extends BaseEntity {

    /** 前缀 {@code INV}。 */
    private String invoiceNo;

    /** VENUE / AGENT / USER。 */
    private String payeeType;

    private String payeeNo;

    private String payeeName;

    private BigDecimal amount;

    /** UAE 增值税登记号（Tax Registration Number）。 */
    private String vatTrn;

    private String currency;

    /** DRAFT / ISSUED / VOID（红冲）。 */
    private String status;

    private String issuedAt;

    /** 出票 PDF 地址，开票后回填。 */
    private String fileUrl;

    // ── 开具 / 作废留痕 ──
    /** 开票人。服务端取登录态，不信入参（与提现审批同一红线）。 */
    private String issuedBy;
    /** 作废原因。**作废时必填** —— 没有原因的作废，稽查时无法解释。 */
    private String voidReason;
    private java.time.LocalDateTime voidedAt;
    private String voidedBy;
    private String invoiceCode;
    private String invoiceNumber;
    private String sourceType;
    private String sourceNo;
}
