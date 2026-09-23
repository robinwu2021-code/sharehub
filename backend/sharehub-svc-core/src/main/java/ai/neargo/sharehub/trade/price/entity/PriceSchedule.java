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

    // —— 结构化时段（V46）——————————————————————————————
    // `period` 存的是**展示串**（`周六-周日 18:00-22:00`、`每天`）。后端拿它判倍率
    // 就得复刻前端那个按中文标签解析的 parser —— 而界面还有英文与阿语。
    // 用展示串做判断，与本项目栽过的「按名字连表」是同一类错。
    // 故判断只读下面这几列，`period` 降级为纯展示。

    /** 生效星期 CSV，{@code 1}=周一 … {@code 7}=周日；空 = 每天。 */
    private String days;

    /** {@code HH:mm}；与 {@link #timeTo} 同时为空 = 全天。 */
    private String timeFrom;

    /** {@code HH:mm}；可跨零点（{@code 22:00-06:00} 合法）。 */
    private String timeTo;

    /** 节假日等日历表达式；**本期不参与计算**，原样保留待日历能力。 */
    private String expr;
}
