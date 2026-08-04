package ai.neargo.sharehub.trade.price.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 计费模板（price_plan，[db-design §5.2]）。
 *
 * <p>命名以 ops-web {@code lib/types/pricing.ts} 的收敛口径为准：
 * {@code freeMinutes}(免费时长/分) · {@code unitMinutes}(计费单位/分) · {@code unitPrice}(单位价) ·
 * {@code capDaily}(日封顶) · {@code capTotal}(总封顶 = 前端 {@code buyoutPrice} 买断价)。
 * 列名保留 DDL 的 {@code cap_total}，VO 层才换成前端的 {@code buyoutPrice}。
 *
 * <p>{@code scope} 是 v1 的 CSV 单列，v2 的多值落法见 {@link PricePlanScope}（[db-design §1.7]）——
 * 两者并存期：本列留作展示，检索一律走拆表。
 *
 * <p>计费模板改动**只影响新订单**：{@code ord_order.price_plan_no} 指向下单当刻的模板，历史单不重算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("price_plan")
public class PricePlan extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {

    /** 业务键，前缀见 [db-design §1.4.1]。 */
    private String planNo;

    private String name;

    /** 免费时长（分钟）。 */
    private Integer freeMinutes;

    /** 计费单位（分钟）。 */
    private Integer unitMinutes;

    /** 单位价（金额 → BigDecimal，[db-design §1.5]）。 */
    private BigDecimal unitPrice;

    /** 日封顶。 */
    private BigDecimal capDaily;

    /** 总封顶 = 买断价（前端 {@code buyoutPrice}）。 */
    private BigDecimal capTotal;

    private String currency;

    /** v1 遗留的 CSV 适用范围；权威落法见 {@link PricePlanScope}。 */
    private String scope;

    /** ACTIVE / DISABLED。 */
    private String status;

    /** 适用设备类型（POWERBANK/EV_PILE/LOCKER）；NULL = 通用方案（取价链的兜底层）。 */
    private String deviceType;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
