package ai.neargo.sharehub.trade.price.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PlanScopeEntry;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricePlanEntry;
import ai.neargo.sharehub.trade.price.entity.PricePlan;
import ai.neargo.sharehub.trade.price.entity.PricePlanScope;
import ai.neargo.sharehub.trade.price.entity.ScopeLevel;
import org.springframework.transaction.annotation.Transactional;
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
                e.getUnitPrice(), e.getCapDaily(), e.getCapTotal(), e.getCurrency(), e.getScope(), e.getStatus(),
                e.getArchivedAt() == null ? null : e.getArchivedAt().toString());
    }

    // ——————————————————————— §1.7 多值拆表 ———————————————————————

    @Override
    public List<PlanScopeEntry> scopesOf(String planNo) {
        if (planNo == null || planNo.isBlank()) return List.of();
        return scopeMapper.selectList(new LambdaQueryWrapper<PricePlanScope>()
                        .eq(PricePlanScope::getPlanNo, planNo)
                        .orderByAsc(PricePlanScope::getId))
                .stream()
                .map(s -> new PlanScopeEntry(s.getId(), s.getPlanNo(), s.getScopeType(), s.getScopeRef(),
                        s.getDeviceType(), s.getVendorCode(), s.getModel(), s.getBrandNo(),
                        s.getPriority(), str(s.getEffectiveFrom()), str(s.getEffectiveTo())))
                .toList();
    }

    @Override
    @Transactional
    public PlanScopeEntry upsertScope(PlanScopeEntry in) {
        if (in == null || in.planNo() == null || in.planNo().isBlank()) {
            throw new IllegalArgumentException("适用范围必须挂在一个方案上");
        }
        ScopeLevel level = ScopeLevel.of(in.scopeType())
                .orElseThrow(() -> new IllegalArgumentException("未知的适用范围层：" + in.scopeType()));
        String ref = level == ScopeLevel.ALL ? ScopeLevel.ALL_REF : trim(in.scopeRef());
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException(level + " 层必须指明具体的" + level.name().toLowerCase() + "编号");
        }
        String deviceType = trim(in.deviceType());
        if (deviceType == null || deviceType.isBlank()) {
            // 设备类型是硬过滤，缺了就会出现「站点规则把充电宝价给了按摩椅」——不许留空
            throw new IllegalArgumentException("适用范围必须指明设备类型");
        }

        PricePlanScope e = in.id() == null ? new PricePlanScope() : scopeMapper.selectById(in.id());
        if (e == null) throw new IllegalArgumentException("适用范围不存在: " + in.id());
        e.setPlanNo(in.planNo());
        e.setScopeType(level.name());
        e.setScopeRef(ref);
        e.setDeviceType(deviceType);
        // 「不限」落**空串**不落 null：MariaDB 的唯一索引把 NULL 视为互不相同，
        // 用 null 表示不限会让 uk_scope_target 在最常见的情形下完全不生效。
        e.setVendorCode(blank(in.vendorCode()));
        e.setModel(blank(in.model()));
        e.setBrandNo(blank(in.brandNo()));
        e.setPriority(in.priority() == null ? 0 : in.priority());
        e.setEffectiveFrom(time(in.effectiveFrom()));
        e.setEffectiveTo(time(in.effectiveTo()));
        try {
            if (e.getId() == null) scopeMapper.insert(e); else scopeMapper.updateById(e);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // uk_scope_target：同一范围已被别的方案占用。这不是「重复提交」，是**业务冲突**，
            // 必须说清楚是谁占着 —— 否则运营只会看到一个「保存失败」，不知道去哪儿解决。
            String owner = scopeMapper.selectList(new LambdaQueryWrapper<PricePlanScope>()
                            .eq(PricePlanScope::getDeviceType, deviceType)
                            .eq(PricePlanScope::getScopeType, level.name())
                            .eq(PricePlanScope::getScopeRef, ref))
                    .stream().map(PricePlanScope::getPlanNo).findFirst().orElse("另一个方案");
            throw new IllegalArgumentException(
                    "这个范围已经由方案 " + owner + " 占用。同一范围只能有一个方案，"
                            + "否则取价要靠优先级猜。请先改那个方案的范围，或改用更具体的层。");
        }
        return new PlanScopeEntry(e.getId(), e.getPlanNo(), e.getScopeType(), e.getScopeRef(),
                e.getDeviceType(), e.getVendorCode(), e.getModel(), e.getBrandNo(),
                e.getPriority(), str(e.getEffectiveFrom()), str(e.getEffectiveTo()));
    }

    @Override
    @Transactional
    public void removeScope(String planNo, Long id) {
        if (id == null) return;
        PricePlanScope e = scopeMapper.selectById(id);
        // 路径上的 planNo 为准：不校验的话，拿到任意 id 就能删别的方案的范围
        if (e == null || !e.getPlanNo().equals(planNo)) {
            throw new IllegalArgumentException("适用范围不存在或不属于该方案: " + id);
        }
        scopeMapper.deleteById(id);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 过滤列专用：空白归一为空串（见 upsertScope 里的说明）。 */
    private static String blank(String s) {
        return s == null ? "" : s.trim();
    }

    private static java.time.LocalDateTime time(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String v = iso.trim();
        // 界面可能给 `2026-09-23`（date 控件）或 `2026-09-23T10:00`（datetime-local）
        return v.length() == 10 ? java.time.LocalDate.parse(v).atStartOfDay()
                : java.time.LocalDateTime.parse(v);
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

    /** 生效期出参用 ISO 串：跨端传时间一律字符串，避免各端各自解 Date 的时区（既有口径）。 */
    private static String str(java.time.LocalDateTime t) {
        return t == null ? null : t.toString();
    }
}
