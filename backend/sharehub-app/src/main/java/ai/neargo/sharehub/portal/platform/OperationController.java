package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.operation.dto.OperationDtos.OperationOverview;
import ai.neargo.sharehub.operation.dto.OperationDtos.PayeeSharingRow;
import ai.neargo.sharehub.operation.dto.OperationDtos.SharingStats;
import ai.neargo.sharehub.operation.dto.OperationDtos.SiteSharingRow;
import ai.neargo.sharehub.operation.dto.OperationDtos.SiteStats;
import ai.neargo.sharehub.operation.service.OperationOverviewService;
import ai.neargo.sharehub.operation.service.SharingQueryService;
import ai.neargo.sharehub.trade.price.service.PriceAdjustmentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 运营管理菜单的后端（清单 OM-S1 / S2 / S4 / S5 / S6）。
 *
 * <p><b>为什么单开一个控制器</b>：这几个端点跨 platform / core / finance 三域，
 * 挂到任何一个域的控制器下都名不正。{@code OpsController} 是场所域的门面，
 * 站点的增删改查在那边；这里只放**读模型与预约调价**。
 *
 * <p>路径沿用前端契约里已经写死的那几个（`lib/api/https/operation.ts`）——
 * 契约是先写的，后端按它实现，不反过来让前端改。
 */
@RestController
public class OperationController {

    private final OperationOverviewService overview;
    private final SharingQueryService sharing;
    private final PriceAdjustmentService adjustments;

    public OperationController(OperationOverviewService overview, SharingQueryService sharing,
                               PriceAdjustmentService adjustments) {
        this.overview = overview;
        this.sharing = sharing;
        this.adjustments = adjustments;
    }

    // ——— 站点概览 / 单站统计 ———

    @GetMapping("/api/ops/operation/overview")
    @PreAuthorize("@perm.can('location:poi:read')")
    public OperationOverview overview(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        return overview.overview(from, to);
    }

    @GetMapping("/api/ops/sites/{siteNo}/stats")
    @PreAuthorize("@perm.can('location:poi:read')")
    public SiteStats siteStats(@PathVariable String siteNo,
                               @RequestParam(required = false) String from,
                               @RequestParam(required = false) String to) {
        return overview.siteStats(siteNo, from, to);
    }

    // ——— 预约调价 ———

    @GetMapping("/api/trade/price-adjustments")
    @PreAuthorize("@perm.can('pricing:rule:read')")
    public PageResult<Map<String, Object>> adjustments(@RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String planNo,
                                                       @RequestParam(required = false) String status) {
        return adjustments.page(page, size, keyword, planNo, status);
    }

    @PostMapping("/api/trade/price-adjustments")
    @PreAuthorize("@perm.can('pricing:rule:config')")
    public Map<String, Object> saveAdjustment(@RequestBody Map<String, Object> body) {
        return adjustments.save(body);
    }

    @PostMapping("/api/trade/price-adjustments/{adjustNo}/cancel")
    @PreAuthorize("@perm.can('pricing:rule:config')")
    public Map<String, Object> cancelAdjustment(@PathVariable String adjustNo,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        return adjustments.cancel(adjustNo, reason == null ? null : String.valueOf(reason));
    }

    @PostMapping("/api/trade/price-adjustments/{adjustNo}/revert")
    @PreAuthorize("@perm.can('pricing:rule:config')")
    public Map<String, Object> revertAdjustment(@PathVariable String adjustNo) {
        return adjustments.revert(adjustNo);
    }

    @PostMapping("/api/trade/price-adjustments/{adjustNo}/retry")
    @PreAuthorize("@perm.can('pricing:rule:config')")
    public Map<String, Object> retryAdjustment(@PathVariable String adjustNo) {
        return adjustments.retry(adjustNo);
    }

    /**
     * 执行到点的调价 / 到期的恢复。**给系统 cron 调**，每分钟一次。
     *
     * <p><b>为什么不用 {@code @Scheduled}</b>：本仓库的既有判断（见 {@code OwnershipReconciler}）——
     * 定时任务在多副本下会并发跑，需要分布式锁，那是与 outbox 投递器一起设计的事。
     * 系统 cron 天然单实例，在单体阶段是更小的代价。部署配置见 `deploy/tencent/cron/`。
     *
     * <p>列表接口也会顺带 tick 一次，所以运营打开页面看到的永远是最新状态 ——
     * 不会出现「明明已经过了生效时间，界面还显示待生效」。
     */
    @PostMapping("/internal/trade/price-adjustments/tick")
    public Map<String, Object> tick() {
        List<String> touched = adjustments.tick();
        return Map.of("touched", touched, "count", touched.size());
    }

    // ——— 分成两视角 ———

    @GetMapping("/api/trade/site-sharing")
    @PreAuthorize("@perm.can('finance:share_rule:read')")
    public PageResult<SiteSharingRow> siteSharing(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String state) {
        return sharing.pageSites(page, size, keyword, state);
    }

    @GetMapping("/api/trade/site-sharing/stats")
    @PreAuthorize("@perm.can('finance:share_rule:read')")
    public SharingStats siteSharingStats() {
        return sharing.stats();
    }

    @GetMapping("/api/trade/payee-sharing")
    @PreAuthorize("@perm.can('finance:share_rule:read')")
    public PageResult<PayeeSharingRow> payeeSharing(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String payeeType) {
        return sharing.pagePayees(page, size, keyword, payeeType);
    }
}
