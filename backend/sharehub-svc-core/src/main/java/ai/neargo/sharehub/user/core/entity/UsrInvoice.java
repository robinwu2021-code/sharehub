package ai.neargo.sharehub.user.core.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * C 端开票申请（usr_invoice，[db-design §6.6]，C-IV-01/03）。
 *
 * <p><b>业务键前缀 {@code UINV}</b>（{@link ai.neargo.sharehub.common.BizKey#INVOICE_USER}）——
 * 与运营侧发票管理的 {@code INV}（{@code fin_invoice}）<b>必须分开</b>，两表同前缀会撞业务键。
 *
 * <p>关联订单不落列：走 {@code fin_invoice_item(invoice_no, order_no)} 明细表（[db-design §1.7]）。
 * {@code title} 是抬头快照，不随 {@code usr_invoice_title} 改名回溯（历史单据留当时的抬头）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_invoice")
public class UsrInvoice extends BaseEntity {

    private String invoiceNo;

    private String cUserNo;

    /** → {@code usr_invoice_title.title_no}。 */
    private String titleNo;

    /** 抬头快照（不回溯）。 */
    private String title;

    private BigDecimal amount;

    private String currency;

    /** APPLIED / ISSUED / REJECTED。 */
    private String status;

    private String fileUrl;

    private String appliedAt;

    /** 空 = 尚未开具。 */
    private String issuedAt;
}
