package ai.neargo.sharehub.trade.pay.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 支付引用（pay_order，[db-design §5.3]）。
 *
 * <p>ADR-005：支付执行委托 neargo nearpay，powerbank **只存支付引用 + 状态镜像**用于驱动订单，
 * 不落渠道明文密钥、不自己对接 PSP。{@code nearpayTxnNo} 是对侧交易引用。
 *
 * <p>状态 INIT / PAYING / PAID / FAILED / CLOSED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_order")
public class PayOrder extends BaseEntity {

    private String payNo;

    /** 关联租借订单。 */
    private String orderNo;

    private String cUserNo;

    /** DEPOSIT / RENT / BUYOUT / RECHARGE / MEMBERSHIP。 */
    private String type;

    private BigDecimal amount;

    private String currency;

    /** 走哪个渠道（→ {@link PayChannel#getChannelCode()}）。 */
    private String channelCode;

    /** INIT / PAYING / PAID / FAILED / CLOSED。 */
    private String status;

    /** nearpay 侧交易引用。 */
    private String nearpayTxnNo;

    private String paidAt;
}
