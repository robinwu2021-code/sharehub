package ai.neargo.sharehub.portal.finance;

import ai.neargo.sharehub.trade.order.entity.OrdTaxInvoice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 租赁税务票据出参（V181）—— 运营端与 C 端共用；不带租户、版本等内部列。 */
public record TaxInvoiceView(String invoiceNo, String kind, String refInvoiceNo, String orderNo, String cUserNo,
                             String payNo, String refundNo,
                             String issuerCountry, String issuerLegalName, String issuerAddress, String issuerTrn,
                             String issuerTitle, BigDecimal taxRatePercent, boolean taxIncluded,
                             BigDecimal netAmount, BigDecimal taxAmount, BigDecimal grossAmount, String currency,
                             String lineDesc, String status, String failReason, LocalDateTime issuedAt,
                             LocalDateTime createdAt) {

    public static TaxInvoiceView of(OrdTaxInvoice t) {
        return new TaxInvoiceView(t.getInvoiceNo(), t.getKind(), t.getRefInvoiceNo(), t.getOrderNo(), t.getCUserNo(),
                t.getPayNo(), t.getRefundNo(), t.getIssuerCountry(), t.getIssuerLegalName(), t.getIssuerAddress(),
                t.getIssuerTrn(), t.getIssuerTitle(), t.getTaxRatePercent(), t.getTaxIncluded() == null || t.getTaxIncluded() != 0,
                t.getNetAmount(), t.getTaxAmount(), t.getGrossAmount(), t.getCurrency(), t.getLineDesc(),
                t.getStatus(), t.getFailReason(), t.getIssuedAt(), t.getCreatedAt());
    }
}
