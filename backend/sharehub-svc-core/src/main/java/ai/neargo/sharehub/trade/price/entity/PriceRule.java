package ai.neargo.sharehub.trade.price.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 差异化定价（price_rule，[db-design §5.2]，菜单：计费定价 › 差异化定价）。
 *
 * <p>取价维度 {@code dimension} = SCENE / LOCATION / SITE，{@code matchRef} 是该维度下的匹配值；
 * 命中多条时按 {@code priority} 排序取第一条（数值小者优先，由计价实现约定）。
 *
 * <p><b>规格冲突（已在交付报告登记）</b>：v1 DDL（{@code pb_core-trade-finance.sql}）只有
 * {@code site_no/scene_type/priority}，v2 关键列 {@code dimension/match_ref/location_name/free_mins/
 * unit_price/day_cap/currency} 的 ALTER 在 {@code pb_core-v2-trade-user.sql} 里**仍是注释状态**；
 * 且 v1 DDL 无 {@code version}/{@code deleted} 两列，而本类按通用约定继承 {@link BaseEntity}。
 * 实体以 db-design 为准（骨架规约 §1），DDL 需补 ALTER 后才能跑通。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("price_rule")
public class PriceRule extends BaseEntity {

    /** 业务键，前缀 {@code PD}（{@code BizKey.PRICING_DIFF}）。 */
    private String ruleNo;

    /** 差异化指向的计费模板。 */
    private String planNo;

    /** SCENE / LOCATION / SITE。 */
    private String dimension;

    /** 该维度下的匹配值（site_no / scene_type / location_no）。 */
    private String matchRef;

    /** 按站点取价（v1 列，与 {@code dimension=SITE} 时的 {@link #matchRef} 语义重叠）。 */
    private String siteNo;

    /** 按场景取价（v1 列；db-design §5.2 里写作 {@code scene}）。 */
    private String sceneType;

    /** 点位名（冗余展示列）。 */
    private String locationName;

    /** 免费时长（分钟）。 */
    private Integer freeMins;

    private BigDecimal unitPrice;

    /** 日封顶。 */
    private BigDecimal dayCap;

    /** 优先级。 */
    private Integer priority;

    private String currency;
}
