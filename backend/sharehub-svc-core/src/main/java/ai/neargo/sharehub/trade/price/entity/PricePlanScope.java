package ai.neargo.sharehub.trade.price.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 计费模板适用范围（price_plan_scope，[db-design §1.7] 的多值拆表）。
 *
 * <p>前端 {@code PricePlan.scope} 是逗号串（渲染方便），**后端不得照抄**——
 * CSV 无法按值检索、无法约束合法值。一行一个值，UK({@code plan_no}, {@code scope_type}, {@code scope_ref})。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("price_plan_scope")
public class PricePlanScope extends BaseEntity {

    /** 所属计费模板。 */
    private String planNo;

    /** SITE / SCENE / ALL。 */
    private String scopeType;

    /** site_no / scene_type / {@code *}（ALL）。 */
    private String scopeRef;
}
