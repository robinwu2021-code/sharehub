package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricePlanEntry;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingDiff;
import ai.neargo.sharehub.trade.price.dto.PriceDtos.PricingSchedule;
import ai.neargo.sharehub.trade.price.entity.PricePlan;
import ai.neargo.sharehub.trade.price.entity.PriceRule;
import ai.neargo.sharehub.trade.price.entity.PriceSchedule;
import ai.neargo.sharehub.trade.price.service.PricePlanService;
import ai.neargo.sharehub.trade.price.service.PricingDiffService;
import ai.neargo.sharehub.trade.price.service.PricingScheduleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
    private final PricingDiffService diffService;
    private final PricingScheduleService scheduleService;

    public PricingController(PricePlanService planService, PricingDiffService diffService,
                             PricingScheduleService scheduleService) {
        this.planService = planService;
        this.diffService = diffService;
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
    public PricePlanEntry createPlan(@RequestBody PricePlan body) {
        return planService.save(body);
    }

    @PostMapping("/price-plans/{planNo}")
    @PreAuthorize("@perm.can('pricing:plan:create')")
    public PricePlanEntry updatePlan(@PathVariable String planNo, @RequestBody PricePlan body) {
        body.setPlanNo(planNo); // 路径为准，忽略 body 里的键，防越权改他单
        return planService.save(body);
    }

    // —— 差异化定价 ——

    @GetMapping("/pricing-diffs")
    @PreAuthorize("@perm.can('pricing:rule:read')")
    public PageResult<PricingDiff> pricingDiffs(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String planNo,
                                                @RequestParam(required = false) String dimension) {
        return diffService.page(page, size, keyword,
                Map.of("planNo", nz(planNo), "dimension", nz(dimension)));
    }

    @PostMapping("/pricing-diffs")
    @PreAuthorize("@perm.can('pricing:rule:update')")
    public PricingDiff createPricingDiff(@RequestBody PriceRule body) {
        return diffService.save(body);
    }

    @PostMapping("/pricing-diffs/{ruleNo}")
    @PreAuthorize("@perm.can('pricing:rule:update')")
    public PricingDiff updatePricingDiff(@PathVariable String ruleNo, @RequestBody PriceRule body) {
        body.setRuleNo(ruleNo);
        return diffService.save(body);
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
    public PricingSchedule createPricingSchedule(@RequestBody PriceSchedule body) {
        return scheduleService.save(body);
    }

    @PostMapping("/pricing-schedules/{ruleNo}")
    @PreAuthorize("@perm.can('pricing:rule:update')")
    public PricingSchedule updatePricingSchedule(@PathVariable String ruleNo, @RequestBody PriceSchedule body) {
        body.setRuleNo(ruleNo);
        return scheduleService.save(body);
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
