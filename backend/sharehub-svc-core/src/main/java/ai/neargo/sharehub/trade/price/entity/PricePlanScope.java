package ai.neargo.sharehub.trade.price.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 收费方案的适用范围（price_plan_scope）—— **取价的唯一依据**（ADR-028）。
 *
 * <p>2026-09-23 之前这张表只被 {@code PricePlanServiceImpl} 读写供界面展示，
 * 取价引擎读的是另一张 {@code price_rule}。两套机制表达同一件事，而且**两套都没生效**
 * （下单时 site/scene 根本没传进取价引擎）。V46 把 {@code price_rule} 迁入本表并退役，
 * 从此「哪个方案适用」只有这一处答案。
 *
 * <h3>层（{@link #scopeType}）与引用（{@link #scopeRef}）</h3>
 * 八档，越靠前越具体：{@code DEVICE > LOCATION > SITE > VENUE > AGENT > SCENE > REGION > ALL}。
 * {@code scopeRef} 是对应层的业务号；{@code ALL} 层固定为 {@code *}。
 *
 * <h3>厂商 / 型号 / 品牌是过滤条件，不是层（ADR-028 §三）</h3>
 * 它们与场所层级**正交**（快充柜可能出现在任何站点）。做成范围行上的可选过滤：
 * 「本站点 × 厂商 X」比「本站点」更具体，同层内胜出。不给它们单独的层，
 * 是为了避免「站点级方案 vs 厂商级方案谁赢」这种没有业务答案的排序问题。
 *
 * <h3>UK 落在范围侧，不含 plan_no</h3>
 * {@code uk_scope_target(device_type, scope_type, scope_ref, vendor_code, model, brand_no)}
 * —— 保证的是「同一范围只能有一个启用中的方案」，这才是取价必然唯一的来源。
 * 旧 UK 含 {@code plan_no}，保证的只是「一个方案不重复登记同一范围」，不是一回事。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("price_plan_scope")
public class PricePlanScope extends BaseEntity {

    /** 所属收费方案。 */
    private String planNo;

    /** 层。见类注释；取值由 {@link ScopeLevel} 把关。 */
    private String scopeType;

    /** 对应层的业务号；{@code ALL} 层为 {@code *}。 */
    private String scopeRef;

    /** 设备类型硬过滤（修缺陷 1：站点规则不看设备类型，按摩椅会按充电宝价收）。 */
    private String deviceType;

    /** 厂商过滤；空 = 不限。 */
    private String vendorCode;

    /** 型号过滤；空 = 不限。 */
    private String model;

    /** 消费者品牌过滤；空 = 不限（待 B1 品牌落地后才有值）。 */
    private String brandNo;

    /** 同层同范围并列时降序裁决。 */
    private Integer priority;

    /** 生效起；空 = 立即。 */
    private LocalDateTime effectiveFrom;

    /** 生效止；空 = 长期。 */
    private LocalDateTime effectiveTo;
}
