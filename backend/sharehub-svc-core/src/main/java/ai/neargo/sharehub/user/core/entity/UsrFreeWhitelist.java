package ai.neargo.sharehub.user.core.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 免费用户白名单（usr_free_whitelist，[db-design §6.1]）。
 *
 * <p>竞品把「免费用户」放订单域，我们归<b>用户域</b>（它本质是用户属性），
 * 并强制标注用途 {@code reason}（枚举，非自由文本）—— {@code ord_order.free_reason} 回指本列，
 * 保证「免费订单」与「白名单」两页口径一致。
 *
 * <p><b>撤销是软删</b>：{@code status=REVOKED} 保留记录（端点 {@code POST /{userNo}/revoke}），
 * 不物理删 —— 免单是钱，撤销记录要能追溯到谁授的、用了多少。
 *
 * <p>{@code quotaType} 与 {@code quotaValue} 联动：
 * {@code UNLIMITED} → {@code quotaValue} 无意义（存 0）；
 * {@code TIMES} → 次数；{@code AMOUNT} → 金额（按 {@code currency}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_free_whitelist")
public class UsrFreeWhitelist extends BaseEntity {

    private String whitelistNo;

    private String regionId;

    private String cUserNo;

    /** INTERNAL_TEST / VIP / BD_DEMO / MERCHANT_SELF。 */
    private String reason;

    /** UNLIMITED / TIMES / AMOUNT。 */
    private String quotaType;

    /** 额度：TIMES=次数，AMOUNT=金额。 */
    private BigDecimal quotaValue;

    /** 已用量，口径同 {@link #quotaValue}。 */
    private BigDecimal usedValue;

    /** {@code quotaType=AMOUNT} 时生效。 */
    private String currency;

    /** 仅日期语义（DATE）。 */
    private String validFrom;

    private String validTo;

    /** 授予人 employee_no（审计用）。 */
    private String grantedBy;

    /** ACTIVE / EXPIRED / REVOKED。 */
    private String status;
}
