package ai.neargo.sharehub.trade.pay.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 支付渠道适用范围（pay_channel_scope，[db-design §1.7] 的多值拆表）。
 *
 * <p>前端 {@code PaymentChannel.countries/currencies/capabilities} 是三个 CSV 串，
 * **后端一律拆行**：一行一个值，UK({@code channel_code}, {@code scope_type}, {@code scope_value})。
 * 这样才能「查 AED 能走哪些渠道」而不是全表 LIKE。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_channel_scope")
public class PayChannelScope extends BaseEntity {

    private String channelCode;

    /** COUNTRY / CURRENCY / CAPABILITY。 */
    private String scopeType;

    /** COUNTRY=ISO alpha-2；CURRENCY=ISO 4217；CAPABILITY=PAY/REFUND/AUTH/CAPTURE/PAYOUT。 */
    private String scopeValue;
}
