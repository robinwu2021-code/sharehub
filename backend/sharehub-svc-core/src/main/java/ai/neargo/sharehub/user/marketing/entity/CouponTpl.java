package ai.neargo.sharehub.user.marketing.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 券模板（coupon_tpl，[db-design §6.3]）—— 运营侧配置「发什么券」，用户手里的券是 {@link UsrCoupon}。
 *
 * <p>{@code value}/{@code threshold} 是金额，按 [db-design §1.5] 必须同表带 {@code currency}
 * —— 该文档点名 coupon_tpl 是 v1「有金额无币种」的 6 处之一，多国开城后无币种即脏数据。
 *
 * <p>{@code issued} 是**已发放张数**，与 usr_coupon 明细必须自洽：发券时 +1（[db-design §1.4]
 * 规定计数不落列的是前端展示派生值，issued 是 DDL 里的真实列，属例外）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coupon_tpl")
public class CouponTpl extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {

    private String tplNo;

    private String name;

    /** CUT（满减）/ DISCOUNT（折扣）。 */
    private String type;

    /** CUT=减免金额；DISCOUNT=折扣率（0..1，按 §1.5「*_rate 是小数」的同族约定）。 */
    private BigDecimal value;

    /** 使用门槛金额。 */
    private BigDecimal threshold;

    private String currency;

    /** 发放规则 JSON（有效期/每人限领/适用场景）。 */
    private String validRule;

    /** 库存总量；0 表示不限。 */
    private Integer stock;

    /** 已发放张数。 */
    private Integer issued;

    /** ACTIVE / PAUSED。 */
    private String status;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
