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
