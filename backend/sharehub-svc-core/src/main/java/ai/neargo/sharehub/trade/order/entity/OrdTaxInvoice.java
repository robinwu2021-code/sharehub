package ai.neargo.sharehub.trade.order.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 租赁订单税务票据（{@code ord_tax_invoice}，V181）—— B2C 发票与贷项通知单同表，{@link #kind} 区分。
 *
 * <p>开票主体、税率、是否含税整份快照在票上：改税务设置只影响之后开的票。
 * {@link #sourceKey} 唯一（{@code PAY:<payNo>} / {@code REFUND:<refundNo>}），同一笔钱只出一张票。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_tax_invoice")
public class OrdTaxInvoice extends BaseEntity {

    private String invoiceNo;
    /** INVOICE / CREDIT_NOTE。 */
    private String kind;
    private String refInvoiceNo;
    private String sourceKey;
    private String orderNo;
    private String cUserNo;
    private String payNo;
    private String refundNo;
    private String issuerCountry;
    private String issuerLegalName;
    private String issuerAddress;
    private String issuerTrn;
    private String issuerTitle;
    private BigDecimal taxRatePercent;
    private Integer taxIncluded;
    private BigDecimal netAmount;
    private BigDecimal taxAmount;
    private BigDecimal grossAmount;
    private String currency;
    private String lineDesc;
    /** ISSUED / BLOCKED。 */
    private String status;
    private String failReason;
    private LocalDateTime issuedAt;
}
