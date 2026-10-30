package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家分成自开票单据（{@code stl_self_bill}，V183，运营方原始需求 C10）。
 * 供方 = 商家、收货方 = 平台开票主体，两边整份快照；一笔提现一张（{@link #withdrawNo} 唯一）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stl_self_bill")
public class StlSelfBill extends BaseEntity {

    private String billNo;
    private String withdrawNo;
    private String agentNo;
    private String supplierName;
    private String supplierAddress;
    private String supplierTrn;
    private String recipientName;
    private String recipientAddress;
    private String recipientTrn;
    private BigDecimal taxRatePercent;
    private BigDecimal netAmount;
    private BigDecimal taxAmount;
    private BigDecimal grossAmount;
    private String currency;
    private String payRef;
    private LocalDateTime issuedAt;
}
