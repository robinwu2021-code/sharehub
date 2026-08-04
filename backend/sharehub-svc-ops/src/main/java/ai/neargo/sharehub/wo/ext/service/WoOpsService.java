package ai.neargo.sharehub.wo.ext.service;

import ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AcceptReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.CloseReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.HandleReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.RejectReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WorkOrderDraft;

/**
 * 工单流转扩展：手工开单 / 接单 / 现场处理 / 完工 / 验收关单 / 驳回退回 / 退回返工。
 *
 * <p><b>与 {@code wo.service.WorkOrderService} 的分工</b>：那边负责列表查询与派单
 * （{@code POST /api/ops/work-orders/{woNo}/dispatch}，已上线，本接口不重复）；
 * 这里补齐 [api/README §六] 剩下的四个动作。状态流转**一律经
 * {@link ai.neargo.sharehub.wo.WoStateMachine}**，非法迁移由它拒。
 */
public interface WoOpsService {

    /**
     * 工单列表（富行）：主表行 + G6 闭环留痕字段（{@code wo_order} 扩展列 ⋈
     * {@code wo_dispatch}/{@code wo_handle} 时间轴），镜像前端 {@code WorkOrder} 全字段。
     */
    ai.neargo.common.core.PageResult<WorkOrder> pageRich(Integer page, Integer size,
                                                         String keyword, String status, String type);

    /**
     * 派单：{@code CREATED → DISPATCHED}（委托主包状态迁移）+ 落 {@code wo_dispatch}
     * action=DISPATCH 时间轴行 —— 主包 {@code WorkOrderService.dispatch} 不写时间轴
     * （main 不知道 ext），编排在这里补齐。
     */
    WorkOrder dispatch(String woNo, String assignee);

    /** 手工开单（补 G6 缺口）。落 {@code wo_order} + 按 SLA 规则算出 {@code wo_sla} 计时行。 */
    WorkOrder create(WorkOrderDraft draft);

    /** 接单：{@code DISPATCHED → ACCEPTED}，留痕 {@code wo_dispatch}，判定响应 SLA 是否超时。 */
    WorkOrder accept(String woNo, AcceptReq req);

    /** 现场处理：留痕 {@code wo_handle}（可多次）；首次处理顺带 {@code ACCEPTED → PROCESSING}。 */
    WorkOrder handle(String woNo, HandleReq req);

    /**
     * 完工：{@code PROCESSING → DONE}，同时落一行 {@code wo_handle}（完工说明/照片/换件标记）。
     *
     * <p><b>与 {@link #close} 是两个动作，不是同义词</b>：完工由处理人报「我修好了」，
     * 关单由验收人判「确实修好了」。[db-design §9A.4] 要求「完工人 ≠ 验收人」，
     * 合成一个端点就没有任何环节能落下完工人这个事实。权限也分家：
     * 完工 {@code workorder:wo:handle}，关单 {@code workorder:wo:close}。
     */
    WorkOrder complete(String woNo, HandleReq req);

    /** 验收关单：{@code DONE → AUDITED → CLOSED}，{@code closeReason} 必填。 */
    WorkOrder close(String woNo, CloseReq req);

    /**
     * 驳回退回：{@code DISPATCHED/ACCEPTED/PROCESSING → CREATED}，{@code reason} 必填。
     * 清空受理人（回到待派单就该重新挑人），并在 {@code wo_dispatch} 落 action=REJECT 一行。
     */
    WorkOrder reject(String woNo, RejectReq req);

    /**
     * 验收不合格退回返工：{@code DONE → PROCESSING}，{@code reason} 必填。
     *
     * <p>与 {@link #reject} 的差别是实打实的，不是措辞差别：起点（DONE vs 派单后）、
     * 终点（PROCESSING vs CREATED）、受理人（保留 vs 清空）、权限
     * （{@code wo:close} 验收人 vs {@code wo:dispatch} 派单人）四项全不同。
     */
    WorkOrder rework(String woNo, RejectReq req);
}
