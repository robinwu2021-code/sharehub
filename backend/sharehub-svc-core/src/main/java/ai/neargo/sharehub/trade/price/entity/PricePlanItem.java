package ai.neargo.sharehub.trade.price.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 计价费用项（{@code price_plan_item}，ADR-018）。
 *
 * <p>一个方案有多个费用项：充电宝一条 {@code TIME_FEE}；充电桩通常是
 * {@code ENERGY_FEE} + {@code SERVICE_FEE}（+ 可选 {@code IDLE_FEE}）。
 * <b>电费与服务费必须分列</b>——多数市场电费不可加价，服务费才是平台收入，
 * 发票/分润/对账三处口径都要求分开。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("price_plan_item")
public class PricePlanItem extends BaseEntity {

    private String itemNo;
    private String planNo;

    /** TIME_FEE / ENERGY_FEE / SERVICE_FEE / IDLE_FEE / HOLD_FEE */
    private String itemType;

    /** MINUTE / KWH / COUNT */
    private String metering;

    /** 免费额度，从计费量里先扣；IDLE_FEE 用它表达宽限期。 */
    private BigDecimal freeQty;

    /** 计费步长（每 N 分钟 / 每 N 度）。 */
    private BigDecimal unitQty;

    private BigDecimal capDaily;
    private BigDecimal capTotal;

    /** CEIL / FLOOR / HALF_UP。**必须显式** —— 舍入口径是对账争议高发区。 */
    private String rounding;

    /** 出账顺序（账单展示与发票行序）。 */
    private Integer sort;

    private String status;
}
