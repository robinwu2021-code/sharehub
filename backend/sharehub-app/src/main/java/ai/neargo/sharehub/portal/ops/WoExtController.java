package ai.neargo.sharehub.portal.ops;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AcceptReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.CloseReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.HandleReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.InspectionPlan;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.RejectReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.SlaRule;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WorkOrderDraft;
import ai.neargo.sharehub.wo.ext.entity.WoInspectionPlan;
import ai.neargo.sharehub.wo.ext.entity.WoSlaRule;
import ai.neargo.sharehub.wo.ext.service.InspectionPlanService;
import ai.neargo.sharehub.wo.ext.service.SlaRuleService;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工单扩展端点（[api/README §六]）：手工开单 / 接单 / 处理 / 完工 / 关单 / 驳回 / 返工
 * + SLA 规则 + 巡检计划。
 *
 * <p>流转动作一律 {@code POST /work-orders/{woNo}/{action}}，状态校验**全部**下沉到
 * {@link ai.neargo.sharehub.wo.WoStateMachine}（唯一定义），非法迁移 → 400。
 * 控制器里没有任何状态判断 —— 前端按钮禁用只是体验，服务端才是防线。
 *
 * <p>与 {@link OpsController} 同为 {@code /api/ops} 前缀，**已被它占用的两个映射不在此重复**：
 * {@code GET /work-orders}（列表/看板）与 {@code POST /work-orders/{woNo}/dispatch}（派单）。
 * 重复映射会让 Spring 启动直接失败。
 *
 * <p>控制器只做路由 + 鉴权 + 调 service。
 */
@RestController
@RequestMapping("/api/ops")
public class WoExtController {

    private final WoOpsService woOpsService;
    private final SlaRuleService slaRuleService;
    private final InspectionPlanService inspectionPlanService;

    public WoExtController(WoOpsService woOpsService, SlaRuleService slaRuleService,
                           InspectionPlanService inspectionPlanService) {
        this.woOpsService = woOpsService;
        this.slaRuleService = slaRuleService;
        this.inspectionPlanService = inspectionPlanService;
    }

    // —— 工单流转（菜单叶：工单管理 › 工单列表/看板 · 处理与验收）——

    /** 手工开单（补 [api/README §6] 的 G6 缺口）。 */
    @PostMapping("/work-orders")
    @PreAuthorize("@perm.can('workorder:wo:create')")
    public WorkOrder createWorkOrder(@RequestBody WorkOrderDraft body) {
        return woOpsService.create(body);
    }

    @PostMapping("/work-orders/{woNo}/accept")
    @PreAuthorize("@perm.can('workorder:wo:process')")
    public WorkOrder accept(@PathVariable String woNo, @RequestBody(required = false) AcceptReq body) {
        return woOpsService.accept(woNo, body);
    }

    @PostMapping("/work-orders/{woNo}/handle")
    @PreAuthorize("@perm.can('workorder:wo:process')")
    public WorkOrder handle(@PathVariable String woNo, @RequestBody(required = false) HandleReq body) {
        return woOpsService.handle(woNo, body);
    }

    /**
     * 完工：{@code PROCESSING → DONE}。与 {@link #close} 是两个动作 ——
     * 处理人报完工、验收人判关单，[db-design §9A.4] 的「完工人 ≠ 验收人」靠这条分界成立。
     * 故权限用 {@code wo:handle}（处理人），不是关单的 {@code wo:audit}。
     */
    @PostMapping("/work-orders/{woNo}/complete")
    @PreAuthorize("@perm.can('workorder:wo:handle')")
    public WorkOrder complete(@PathVariable String woNo, @RequestBody(required = false) HandleReq body) {
        return woOpsService.complete(woNo, body);
    }

    /** 完成 / 审核关单。{@code closeReason} 必填，空则 400。 */
    @PostMapping("/work-orders/{woNo}/close")
    @PreAuthorize("@perm.can('workorder:wo:audit')")
    public WorkOrder close(@PathVariable String woNo, @RequestBody CloseReq body) {
        return woOpsService.close(woNo, body);
    }

    /**
     * 驳回退回待派单：{@code DISPATCHED/ACCEPTED/PROCESSING → CREATED}，{@code reason} 必填。
     * 权限跟派单同一个码（{@code wo:dispatch}）—— 退回的下一步就是重派，判断由同一个人做。
     */
    @PostMapping("/work-orders/{woNo}/reject")
    @PreAuthorize("@perm.can('workorder:wo:dispatch')")
    public WorkOrder reject(@PathVariable String woNo, @RequestBody RejectReq body) {
        return woOpsService.reject(woNo, body);
    }

    /**
     * 验收不合格退回返工：{@code DONE → PROCESSING}，{@code reason} 必填，受理人不变。
     * 权限跟验收关单同属验收人的判定动作。
     *
     * <p>用的是 {@code workorder:wo:close} 而不是上面 {@code close()} 那个 {@code :audit}：
     * {@code :audit}/{@code :process} 是 [功能权限清单 §7] 已废弃的旧码
     * （2026-07-29 统一为 {@code :handle}/{@code :close}），ops-web 的按钮也按新码渲染
     * （{@code canClose = wo:close}）。新端点一律用新码；上面三个老端点的旧码是既存分歧，
     * 见交付报告，此处不顺手改（会改变现有角色的可见范围，须单独放行）。
     */
    @PostMapping("/work-orders/{woNo}/rework")
    @PreAuthorize("@perm.can('workorder:wo:close')")
    public WorkOrder rework(@PathVariable String woNo, @RequestBody RejectReq body) {
        return woOpsService.rework(woNo, body);
    }

    // —— SLA 规则配置（菜单叶：工单管理 › SLA 管理）——

    @GetMapping("/sla-rules")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public PageResult<SlaRule> slaRules(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String woType,
                                        @RequestParam(required = false) String active) {
        return slaRuleService.page(page, size, keyword,
                Map.of("woType", nz(woType), "active", nz(active)));
    }

    @GetMapping("/sla-rules/{slaNo}")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public SlaRule slaRule(@PathVariable String slaNo) {
        return slaRuleService.get(slaNo);
    }

    @PostMapping("/sla-rules")
    @PreAuthorize("@perm.can('workorder:wo:update')")
    public SlaRule createSlaRule(@RequestBody WoSlaRule body) {
        return slaRuleService.save(body);
    }

    @PostMapping("/sla-rules/{slaNo}")
    @PreAuthorize("@perm.can('workorder:wo:update')")
    public SlaRule updateSlaRule(@PathVariable String slaNo, @RequestBody WoSlaRule body) {
        body.setSlaNo(slaNo);
        return slaRuleService.save(body);
    }

    // —— 巡检计划（菜单叶：工单管理 › 巡检计划）——

    @GetMapping("/inspection-plans")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public PageResult<InspectionPlan> inspectionPlans(@RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String active) {
        return inspectionPlanService.page(page, size, keyword, Map.of("active", nz(active)));
    }

    @GetMapping("/inspection-plans/{planNo}")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public InspectionPlan inspectionPlan(@PathVariable String planNo) {
        return inspectionPlanService.get(planNo);
    }

    @PostMapping("/inspection-plans")
    @PreAuthorize("@perm.can('workorder:wo:update')")
    public InspectionPlan createInspectionPlan(@RequestBody WoInspectionPlan body) {
        return inspectionPlanService.save(body);
    }

    @PostMapping("/inspection-plans/{planNo}")
    @PreAuthorize("@perm.can('workorder:wo:update')")
    public InspectionPlan updateInspectionPlan(@PathVariable String planNo, @RequestBody WoInspectionPlan body) {
        body.setPlanNo(planNo);
        return inspectionPlanService.save(body);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 立即执行一次巡检计划。**不是定时任务** ——
     * 定时调度需分布式锁（多副本会并发跑），与 outbox 投递器一起设计。
     */
    @PostMapping("/inspection-plans/{planNo}/run")
    @PreAuthorize("@perm.can('workorder:inspection:update')")
    public Object runInspectionPlan(@PathVariable String planNo) {
        return inspectionPlanService.run(planNo);
    }
}
