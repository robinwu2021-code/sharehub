package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PlanScopeEntry;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricePlanEntry;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricePlanReq;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingSchedule;
import ai.neargo.sharehub.trade.price.entity.PricePlan;
import ai.neargo.sharehub.trade.price.entity.PriceSchedule;
import ai.neargo.sharehub.trade.price.service.PricePlanService;
import ai.neargo.sharehub.trade.price.service.PricingScheduleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 计费定价（[api/README §4.2]，菜单：计费定价 3 叶 —— 计费模板 / 差异化定价 / 活动时段价）。
 *
 * <p>{@code GET /api/trade/price-plans} 原在 {@code TradeController} 的 SeedData 骨架，
 * 已随骨架退役迁入本类，计费模板读写就此收口在一个控制器。
 *
 * <p>差异化定价与活动时段价共用权限码 {@code pricing:rule:read}/{@code :update}（api/README 原文如此）。
 */
@RestController
@RequestMapping("/api/trade")
public class PricingController {

    private final PricePlanService planService;
    private final PricingScheduleService scheduleService;

    public PricingController(PricePlanService planService, PricingScheduleService scheduleService) {
        this.planService = planService;
        this.scheduleService = scheduleService;
    }

    // —— 计费模板 ——

    @GetMapping("/price-plans")
    @PreAuthorize("@perm.can('pricing:plan:read')")
    public PageResult<PricePlanEntry> pricePlans(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status) {
        return planService.page(page, size, keyword, Map.of("status", nz(status)));
    }

    @PostMapping("/price-plans")
    @PreAuthorize("@perm.can('pricing:plan:create')")
    public PricePlanEntry createPlan(@RequestBody PricePlanReq body) {
        return planService.save(body.toEntity());
    }

    @PostMapping("/price-plans/{planNo}")
    @PreAuthorize("@perm.can('pricing:plan:create')")
    public PricePlanEntry updatePlan(@PathVariable String planNo,
                                     @RequestBody PricePlanReq body) {
        PricePlan e = body.toEntity();
        e.setPlanNo(planNo); // 路径为准，忽略 body 里的键，防越权改他单
        return planService.save(e);
    }

    /*
     * 「差异化定价」的三个端点已于 2026-09-23 退役（ADR-028 / V46）。
     *
     * 它读写的 `price_rule` 表达的是「站点/场景 → 方案」，与收费方案上的「适用范围」
     * （`price_plan_scope`）说的是同一件事。两套机制并存，而**取价引擎两套都没读到**：
     * 引擎读 price_rule，但下单处从未把站点传进来。留着一个已经没有表的写入口，
     * 只会让人以为配了有用。范围维护统一在「运营管理 › 收费方案 › 适用范围」。
     */

    // —— 适用范围（取价的唯一依据，ADR-028）——
    //
    // 2026-09-23 补：这三个端点此前**根本不存在** —— 服务层早有 scopesOf/replaceScope，
    // 但没有任何 HTTP 出口。于是「适用范围」在界面上只是一个自由文本描述框，
    // 真正被引擎读的那张表**只有种子能写**。「配了不生效」的另一半是「压根没法配」。

    @GetMapping("/price-plans/{planNo}/scopes")
    @PreAuthorize("@perm.can('pricing:plan:read')")
    public List<PlanScopeEntry> planScopes(@PathVariable String planNo) {
        return planService.scopesOf(planNo);
    }

    @PostMapping("/price-plans/{planNo}/scopes")
    @PreAuthorize("@perm.can('pricing:plan:create')")
    public PlanScopeEntry savePlanScope(@PathVariable String planNo, @RequestBody PlanScopeEntry body) {
        // 路径为准，忽略 body 里的 planNo —— 防越权改他单（与 updatePlan 同一处理）
        return planService.upsertScope(new PlanScopeEntry(body.id(), planNo, body.scopeType(),
                body.scopeRef(), body.deviceType(), body.vendorCode(), body.model(), body.brandNo(),
                body.priority(), body.effectiveFrom(), body.effectiveTo()));
    }

    /** 契约禁止 delete*，用 remove（软删语义由实体的 @TableLogic 负责）。 */
    @PostMapping("/price-plans/{planNo}/scopes/{id}/remove")
    @PreAuthorize("@perm.can('pricing:plan:create')")
    public OkResult removePlanScope(@PathVariable String planNo, @PathVariable Long id) {
        planService.removeScope(planNo, id);
        return new OkResult(true);
    }

    // —— 活动 / 时段价 ——

    @GetMapping("/pricing-schedules")
    @PreAuthorize("@perm.can('pricing:rule:read')")
    public PageResult<PricingSchedule> pricingSchedules(@RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String active) {
        return scheduleService.page(page, size, keyword, Map.of("active", nz(active)));
    }

    @PostMapping("/pricing-schedules")
    @PreAuthorize("@perm.can('pricing:rule:update')")
    public PricingSchedule createPricingSchedule(
            @RequestBody ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingScheduleReq body) {
        return scheduleService.save(toEntity(null, body));
    }

    @PostMapping("/pricing-schedules/{ruleNo}")
    @PreAuthorize("@perm.can('pricing:rule:update')")
    public PricingSchedule updatePricingSchedule(@PathVariable String ruleNo,
            @RequestBody ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingScheduleReq body) {
        return scheduleService.save(toEntity(ruleNo, body));
    }

    /**
     * 入参 → 实体。{@code active} 在契约里是布尔、在库里是 0/1，转换只该有这一处。
     * 让前端传 0/1，或者让实体直接当请求体，都会把这个差异漏到某个没人测的角落。
     */
    private static PriceSchedule toEntity(String ruleNo,
            ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingScheduleReq in) {
        PriceSchedule e = new PriceSchedule();
        e.setRuleNo(ruleNo != null ? ruleNo : in.ruleNo());   // 路径为准
        e.setRegionId(in.regionId());
        e.setName(in.name());
        e.setPeriod(in.period());
        e.setDays(in.days());
        e.setTimeFrom(in.timeFrom());
        e.setTimeTo(in.timeTo());
        e.setExpr(in.expr());
        e.setMultiplier(in.multiplier());
        e.setActive(in.active() == null ? null : (in.active() ? 1 : 0));
        return e;
    }

    /** {@code Map.of} 不接受 null，统一转空串；空串在基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 归档PricePlan。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/price-plans/{no}/archive")
    @PreAuthorize("@perm.can('pricing:plan:update')")
    public Object archivePricePlan(@PathVariable String no) {
        return planService.archive(no);
    }

    /** 取消归档PricePlan：清空时间戳，回到默认列表。 */
    @PostMapping("/price-plans/{no}/unarchive")
    @PreAuthorize("@perm.can('pricing:plan:update')")
    public Object unarchivePricePlan(@PathVariable String no) {
        return planService.unarchive(no);
    }
}
