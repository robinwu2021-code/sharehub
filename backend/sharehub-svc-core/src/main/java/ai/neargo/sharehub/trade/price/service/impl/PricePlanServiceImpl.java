package ai.neargo.sharehub.trade.price.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PlanScopeEntry;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricePlanEntry;
import ai.neargo.sharehub.trade.price.entity.PricePlan;
import ai.neargo.sharehub.trade.price.entity.PricePlanScope;
import ai.neargo.sharehub.trade.price.mapper.PricePlanMapper;
import ai.neargo.sharehub.trade.price.mapper.PricePlanScopeMapper;
import ai.neargo.sharehub.trade.price.service.PricePlanService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/** 计费模板实现：CRUD 走基类，{@code price_plan_scope} 多值拆表手写。 */
@Service
public class PricePlanServiceImpl extends AbstractCrudService<PricePlan, PricePlanEntry> implements PricePlanService {

    /** 业务键前缀（[db-design §1.4.1]；{@code BizKey} 尚未登记 price_plan，见交付报告）。 */
    private static final String PLAN_PREFIX = "PP";

    private final PricePlanScopeMapper scopeMapper;

    public PricePlanServiceImpl(PricePlanMapper mapper, PricePlanScopeMapper scopeMapper) {
        super(mapper);
        this.scopeMapper = scopeMapper;
    }

    @Override
    protected String keyColumn() {
        return "plan_no";
    }

    @Override
    protected String keyOf(PricePlan e) {
        return e.getPlanNo();
    }

    @Override
    protected void setKey(PricePlan e, String no) {
        e.setPlanNo(no);
    }

    @Override
    protected String keyPrefix() {
        return PLAN_PREFIX;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"plan_no", "name", "scope"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"status", "currency"};
    }

    @Override
    protected void beforeCreate(PricePlan e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("ACTIVE");
        if (e.getCurrency() == null || e.getCurrency().isBlank()) e.setCurrency("AED");
    }

    @Override
    protected PricePlanEntry toVO(PricePlan e) {
        return new PricePlanEntry(e.getPlanNo(), e.getName(), e.getFreeMinutes(), e.getUnitMinutes(),
                e.getUnitPrice(), e.getCapDaily(), e.getCapTotal(), e.getCurrency(), e.getScope(), e.getStatus());
    }

    // ——————————————————————— §1.7 多值拆表 ———————————————————————

    @Override
    public List<PlanScopeEntry> scopesOf(String planNo) {
        if (planNo == null || planNo.isBlank()) return List.of();
        return scopeMapper.selectList(new LambdaQueryWrapper<PricePlanScope>()
                        .eq(PricePlanScope::getPlanNo, planNo)
                        .orderByAsc(PricePlanScope::getId))
                .stream()
                .map(s -> new PlanScopeEntry(s.getPlanNo(), s.getScopeType(), s.getScopeRef()))
                .toList();
    }

    @Override
    public List<String> scopeRefs(String planNo, String scopeType) {
        if (planNo == null || planNo.isBlank() || scopeType == null || scopeType.isBlank()) return List.of();
        return scopeMapper.selectList(new LambdaQueryWrapper<PricePlanScope>()
                        .eq(PricePlanScope::getPlanNo, planNo)
                        .eq(PricePlanScope::getScopeType, scopeType)
                        .orderByAsc(PricePlanScope::getId))
                .stream()
                .map(PricePlanScope::getScopeRef)
                .toList();
    }

    @Override
    @Transactional
    public void replaceScope(String planNo, String scopeType, List<String> scopeRefs) {
        if (planNo == null || planNo.isBlank()) throw new IllegalArgumentException("planNo 不能为空");
        if (scopeType == null || scopeType.isBlank()) throw new IllegalArgumentException("scopeType 不能为空");

        // 先软删该类型下的全部旧行（@TableLogic），再逐行插入 —— UK 三列，重复值由库兜底
        scopeMapper.delete(new LambdaQueryWrapper<PricePlanScope>()
                .eq(PricePlanScope::getPlanNo, planNo)
                .eq(PricePlanScope::getScopeType, scopeType));

        if (scopeRefs == null) return;
        scopeRefs.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .forEach(v -> {
                    PricePlanScope row = new PricePlanScope();
                    row.setTenantId(TENANT_MAIN);
                    row.setPlanNo(planNo);
                    row.setScopeType(scopeType);
                    row.setScopeRef(v);
                    scopeMapper.insert(row);
                });
    }

    @Override
    public void replaceScopeCsv(String planNo, String scopeType, String csv) {
        List<String> values = (csv == null || csv.isBlank())
                ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        replaceScope(planNo, scopeType, values);
    }
}
