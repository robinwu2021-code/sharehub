package ai.neargo.sharehub.trade.price.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 活动 / 时段价（price_schedule，[db-design §5.2]，菜单：计费定价 › 活动时段价）。
 *
 * <p><b>{@link #multiplier} 是倍率不是比率</b>：{@code DECIMAL(6,4)}，取值**可 &gt; 1**
 * （如高峰 1.5000、活动 0.8000），是 [db-design §1.5]「比率列 0..1」的**显式例外**。
 * 因此必须用 {@link BigDecimal}，且**不要**套用 {@code *_rate} 的 0..1 校验。
 *
 * <p>{@code period} 是时段/节假日表达式（如 {@code 18:00-23:00}、{@code HOLIDAY:EID}），
 * 解析规则由计价实现约定，本表只存表达式原文。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("price_schedule")
public class PriceSchedule extends BaseEntity {

    /** 业务键，前缀 {@code PS}（{@code BizKey.PRICING_SCHEDULE}）。注意本表业务键列名就叫 {@code rule_no}。 */
    private String ruleNo;

    /** 生效区域（可空 = 全域）。 */
    private String regionId;

    private String name;

    /** 时段 / 节假日表达式。 */
    private String period;

    /** 倍率，可 &gt; 1。见类注释。 */
    private BigDecimal multiplier;

    /** 是否启用（TINYINT(1) → Integer）。 */
    private Integer active;
}
