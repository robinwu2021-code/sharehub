package ai.neargo.sharehub.trade.price.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PlanScopeEntry;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricePlanEntry;
import ai.neargo.sharehub.trade.price.entity.PricePlan;

import java.util.List;

/**
 * 计费模板。纯配置读写，无状态机 → 继承通用 CRUD。
 *
 * <p>额外提供 [db-design §1.7] 多值拆表的读写：前端传 CSV，服务端拆成 {@code price_plan_scope} 行。
 */
public interface PricePlanService extends CrudService<PricePlan, PricePlanEntry> {

    /** 按模板读回全部适用范围行。 */
    List<PlanScopeEntry> scopesOf(String planNo);

    /**
     * 新增 / 修改一行适用范围（{@code id} 为空即新增）。
     *
     * <p>为什么要行级写而不是沿用下面的 {@code replaceScope(planNo, scopeType, refs)}：
     * 后者只能表达「这个方案在这一层有哪几个引用」，而 ADR-028 之后一行上还有
     * 设备类型、厂商/型号/品牌过滤、优先级、生效期 —— CSV 装不下。
     *
     * @throws IllegalArgumentException 该范围已被别的方案占用（uk_scope_target）——
     *         「同一范围只能有一个启用中的方案」是取价必然唯一的保证，冲突必须当场说清楚，
     *         而不是让两个方案在库里并存、由 priority 去猜
     */
    PlanScopeEntry upsertScope(PlanScopeEntry entry);

    /** 删一行适用范围。本表没有业务号（它不是一个独立对象，只是方案的一条范围），故按 id。 */
    void removeScope(String planNo, Long id);

    /** 按模板 + 范围类型（SITE/SCENE/ALL）读回一组 {@code scope_ref} 值。 */
    List<String> scopeRefs(String planNo, String scopeType);

    /**
     * 整组替换某一范围类型下的值（先软删旧行再插新行）。
     *
     * @param scopeRefs 传空集合等价于清空该类型
     */
    void replaceScope(String planNo, String scopeType, List<String> scopeRefs);

    /** CSV 入口：把前端的 {@code "ST001,ST002"} 拆成行。空串 = 清空。 */
    void replaceScopeCsv(String planNo, String scopeType, String csv);
}
