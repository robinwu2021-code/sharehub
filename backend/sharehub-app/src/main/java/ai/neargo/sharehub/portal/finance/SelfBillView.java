package ai.neargo.sharehub.portal.finance;

import ai.neargo.sharehub.finance.entity.StlSelfBill;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 自开票单据出参（C10，V183）；不带租户、版本等内部列。 */
public record SelfBillView(String billNo, String withdrawNo, String agentNo, String supplierName, String supplierAddress,
                           String supplierTrn, String recipientName, String recipientTrn, BigDecimal taxRatePercent,
                           BigDecimal netAmount, BigDecimal taxAmount, BigDecimal grossAmount, String currency,
                           String payRef, LocalDateTime issuedAt) {

    public static SelfBillView of(StlSelfBill b) {
        return new SelfBillView(b.getBillNo(), b.getWithdrawNo(), b.getAgentNo(), b.getSupplierName(), b.getSupplierAddress(),
                b.getSupplierTrn(), b.getRecipientName(), b.getRecipientTrn(), b.getTaxRatePercent(), b.getNetAmount(),
                b.getTaxAmount(), b.getGrossAmount(), b.getCurrency(), b.getPayRef(), b.getIssuedAt());
    }
}
