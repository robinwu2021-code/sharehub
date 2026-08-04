package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 结算明细（stl_settlement_detail，[db-design §5.5]）—— 结算单的构成项，无独立业务键。
 *
 * <p>{@code refType=SHARE} 时 {@code refNo} 指向 {@code share_record.record_no}；
 * {@code refType=ORDER} 时指向 {@code ord_order.order_no}。币种随主单 {@link StlSettlement#getCurrency()}，
 * 本表不再冗余（[db-design §5.5] 关键列未列 currency）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stl_settlement_detail")
public class StlSettlementDetail extends BaseEntity {

    private String settleNo;

    /** ORDER / SHARE。 */
    private String refType;

    private String refNo;

    private BigDecimal amount;
}
