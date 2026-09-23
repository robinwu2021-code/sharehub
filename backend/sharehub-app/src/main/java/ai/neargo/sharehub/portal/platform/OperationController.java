package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.operation.dto.OperationDtos.OperationOverview;
import ai.neargo.sharehub.operation.dto.OperationDtos.PayeeSharingRow;
import ai.neargo.sharehub.operation.dto.OperationDtos.SharingStats;
import ai.neargo.sharehub.operation.dto.OperationDtos.SiteSharingRow;
import ai.neargo.sharehub.operation.dto.OperationDtos.SiteStats;
import ai.neargo.sharehub.operation.service.OperationOverviewService;
import ai.neargo.sharehub.operation.service.SharingQueryService;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.loc.ext.dto.SiteAgentDtos.SiteAgentRow;
import ai.neargo.sharehub.loc.ext.service.SiteAgentService;
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
    private final SiteAgentService siteAgents;

    public OperationController(OperationOverviewService overview, SharingQueryService sharing,
                               PriceAdjustmentService adjustments, SiteAgentService siteAgents) {
        this.overview = overview;
        this.sharing = sharing;
        this.adjustments = adjustments;
        this.siteAgents = siteAgents;
    }

    // ——— 站点概览 / 单站统计 ———

    @GetMapping("/api/ops/operation/overview")
    /*
     * D6d 权限对账：此前判的是 `location:poi:read` —— 一个覆盖 4 个端点、3 个菜单叶的**宽码**，
     * 而本端点只是个只读看板。功能权限清单第 67 行本来就为它定了专属码
     * `location:overview:read`（给 OPS/FIN/BD/VIEW），**后端却没有任何端点用过它**。
     *
     * 后果是前端拿一个没人判的码渲染菜单入口、后端拿另一个更宽的码判访问：
     * 财务看得到「站点概览」却打不开（没有 poi:read），而给财务补 poi:read
     * 又会连带解锁「站点管理」「点位管理」两个它不该有的入口。改判专属码，两头都正了。
     */
    @PreAuthorize("@perm.can('location:overview:read')")
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

    /**
     * 新建或编辑调价单。
     *
     * <p><b>两个路径映射同一个方法</b>，与 {@code AgentController#save} /
     * {@code OpsController} 的机柜·站点·点位同一房规：前端 {@code saveXxx} 一律写成
     * {@code x.no ? POST /xxx/{no} : POST /xxx}，两种都得接得住。
     *
     * <p>2026-09-23 之前只映射了集合路径，编辑一张调价单必 404 —— 而
     * {@code PriceAdjustmentServiceImpl.save} 本来就从 body 读 {@code adjustNo} 做 upsert，
     * 能力是全的，缺的只是这一条路由。本地 ops-web 跑 mock 所以一直没暴露。
     *
     * <p>路径上的 {@code adjustNo} 会覆盖 body 里的同名字段：**路径是更强的意图表达**，
     * 两者不一致时以路径为准，否则「在 A 的编辑页保存出 B」这种事没有任何东西拦得住。
     */
    @PostMapping({"/api/trade/price-adjustments", "/api/trade/price-adjustments/{adjustNo}"})
    @PreAuthorize("@perm.can('pricing:rule:config')")
    public Map<String, Object> saveAdjustment(@PathVariable(required = false) String adjustNo,
                                              @RequestBody Map<String, Object> body) {
        Map<String, Object> in = body == null ? new java.util.HashMap<>() : new java.util.HashMap<>(body);
        if (adjustNo != null && !adjustNo.isBlank()) in.put("adjustNo", adjustNo);
        return adjustments.save(in);
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
    public Map<String, Object> tick(jakarta.servlet.http.HttpServletRequest req) {
        /*
         * **只接受本机回环调用**。这个端点会改价格，不能因为「nginx 没暴露 /internal」
         * 就当它安全 —— 任何能在这台机器上起进程的东西都够得着 8082，
         * 而依赖反代配置来保证鉴权，等于把安全边界放在一个随时可能被改的文件里。
         */
        String ip = req.getRemoteAddr();
        if (!"127.0.0.1".equals(ip) && !"0:0:0:0:0:0:0:1".equals(ip) && !"::1".equals(ip)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "该端点只接受本机调用");
        }
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

    // —— 站点上的伙伴责任（ADR-027 §三 / TDD-A2 第 1 批）——
    //
    // 只有运营方能配：责任直接决定分钱，伙伴自助是 L3 的事。
    // 权限码复用 location:poi:*（它就是「站点档案维护」的码）—— 责任是站点档案的一部分，
    // 不为它新造一个码：新码要先补 SSOT、再给角色、再对前后端两处，而语义与站点维护并无区别。

    @GetMapping("/api/ops/sites/{siteNo}/agents")
    @PreAuthorize("@perm.can('location:poi:read')")
    public List<SiteAgentRow> siteAgents(@PathVariable String siteNo) {
        return siteAgents.ofSite(siteNo);
    }

    @PostMapping("/api/ops/sites/{siteNo}/agents")
    @PreAuthorize("@perm.can('location:poi:update')")
    public SiteAgentRow saveSiteAgent(@PathVariable String siteNo, @RequestBody SiteAgentRow body) {
        return siteAgents.upsert(siteNo, body);   // 路径为准，body 里的 siteNo 不采信
    }

    /** 契约禁止 delete*，用 remove。撤销就是删这一行（见实体注释：数组列的读-改-写会静默覆盖）。 */
    @PostMapping("/api/ops/sites/{siteNo}/agents/{id}/remove")
    @PreAuthorize("@perm.can('location:poi:update')")
    public OkResult removeSiteAgent(@PathVariable String siteNo, @PathVariable Long id) {
        siteAgents.remove(siteNo, id);
        return new OkResult(true);
    }
}
