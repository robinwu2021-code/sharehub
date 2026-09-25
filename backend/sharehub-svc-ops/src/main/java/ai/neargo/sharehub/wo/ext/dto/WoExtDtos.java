package ai.neargo.sharehub.wo.ext.dto;

/**
 * wo/ext 子域出参 VO 与动作入参。字段镜像 {@code ops-web/lib/types/workorder.ts}。
 *
 * <p>不往顶层 {@code dto/Dto.java} 追加（[骨架规约 §4]）；工单主体 VO 继续复用
 * {@code WorkOrder}（{@code wo} 主包已在用，两处并存会立刻分叉）。
 */
public final class WoExtDtos {

    private WoExtDtos() {
    }

    /** SLA 规则行，镜像前端 {@code SlaRule}。 */
    public record SlaRule(String slaNo, String woType, Integer responseMins, Integer resolveMins,
                          String escalateTo, Boolean active) {
    }

    /** 巡检计划行，镜像前端 {@code InspectionPlan}（含上次执行回显，V31）。 */
    public record InspectionPlan(String planNo, String route, String frequency, String cron,
                                 String nextAt, String assignee, Boolean active,
                                 String lastRunAt, String lastRunPeriod,
                                 java.util.List<String> lastRunWoNos) {
    }

    /** 手工开单入参，镜像前端 {@code WorkOrderDraft}（补 [api/README §6] 的 G6 缺口）。 */
    public record WorkOrderDraft(String type, String source, String sourceNo, String priority,
                                 String cabinetNo, String locationNo, String locationName,
                                 String agentNo, String siteNo, String description,
                                 String expectedAt) {
    }

    /** 接单入参。 */
    public record AcceptReq(String assigneeNo) {
    }

    /**
     * 巡检派生（D4）：巡检现场发现问题直接开单，来源记巡检单号。type ∈ FAULT / REFILL / CLEAN；
     * cabinetNo 空 = 巡检单上的机柜；priority 空 = MEDIUM。
     */
    public record DeriveReq(String type, String priority, String cabinetNo, String description) {
    }

    /** 工单成本汇总一行（G4）：按承担方（站点 / 代理）聚合。 */
    public record CostRow(String bearerType, String bearerNo, long orders, java.math.BigDecimal total, String currency) {
    }

    /** 平台接管（F4）：employeeNo 空 = 按站点员工责任人 / 区域负载自动选。 */
    public record TakeoverReq(String employeeNo, String reason) {
    }

    /** 现场处理入参，镜像前端 {@code WorkOrderHandlePayload}（+ 照片/换件标记）。 */
    public record HandleReq(String assigneeNo, String photos, String handleNote,
                            Boolean partChanged, Boolean deviceChanged,
                            // 2026-09-25 完工收紧：故障原因分类（字典 wo_fault_reason）+ 现场照片（文件服务 fileNo）
                            String faultReasonCode, java.util.List<String> fileNos,
                            // 批次 C：装机现场扫码的点位（C5）· 撤机现场清点的宝数（C8，撤机单必填）
                            String locationNo, Integer countedQty,
                            // 批次 G4：工单成本（配件 / 人工金额，完工时填；归属见 WoOpsServiceImpl#recordCost）
                            java.math.BigDecimal partCost, java.math.BigDecimal laborCost) {

        /** 兼容旧构造点。 */
        public HandleReq(String assigneeNo, String photos, String handleNote, Boolean partChanged, Boolean deviceChanged) {
            this(assigneeNo, photos, handleNote, partChanged, deviceChanged, null, null, null, null, null, null);
        }

        /** 兼容构造点（无装机 / 撤机现场数据）。 */
        public HandleReq(String assigneeNo, String photos, String handleNote, Boolean partChanged, Boolean deviceChanged,
                         String faultReasonCode, java.util.List<String> fileNos) {
            this(assigneeNo, photos, handleNote, partChanged, deviceChanged, faultReasonCode, fileNos, null, null, null, null);
        }

        /** 兼容构造点（无成本）。 */
        public HandleReq(String assigneeNo, String photos, String handleNote, Boolean partChanged, Boolean deviceChanged,
                         String faultReasonCode, java.util.List<String> fileNos, String locationNo, Integer countedQty) {
            this(assigneeNo, photos, handleNote, partChanged, deviceChanged, faultReasonCode, fileNos, locationNo, countedQty, null, null);
        }
    }

    /**
     * 业务告警的开单草稿（{@code openOrAttach}）。
     *
     * @param mergeKey 合并键，写 {@code wo_order.source_ref}：同键未完结的工单直接挂靠
     */
    public record AlarmDraft(String type, String priority, String mergeKey, String cabinetNo, String siteNo,
                             String description, String alarmNo) {
    }

    /** 完工复核的一项（每条关联告警一项）。 */
    public record ReviewItem(String alarmNo, String code, boolean passed, String note) {
    }

    /** 派单候选人。{@code siteOwner} = 站点运维责任人（置顶）。 */
    public record AssigneeCandidate(String type, String no, String name, boolean siteOwner) {
    }

    public record WoSummary(long toDispatch, long dueSoon, long overdue, long reviewFailed) {
    }

    /**
     * 工单列表筛选（2026-09-25 追加）。
     *
     * @param slaState DUE_SOON 两小时内到期 / OVERDUE 已超时（只看未完工的）
     */
    public record WoQuery(Integer page, Integer size, String keyword, String status, String type, String priority,
                          String source, String siteNo, String assigneeNo, String slaState, String reviewStatus) {
    }

    /** 处理时间线的一条：派单时间轴（wo_dispatch）与处理记录（wo_handle）合并按时间排序。 */
    public record TimelineItem(String kind, String action, String actor, String note, String faultReasonCode,
                               java.util.List<String> fileNos, String at) {
    }

    /** 工单详情（wo 侧）：行 + 时间线 + 现场照片。关联告警由 portal 层编排追加。 */
    public record WorkOrderDetail(ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder order, java.util.List<TimelineItem> timeline,
                                  java.util.List<ai.neargo.sharehub.api.platform.dto.FileRef> photos) {
    }

    /**
     * 回退入参（{@code /reject} 驳回退回 与 {@code /rework} 验收退回返工共用），
     * 镜像前端两个动作都只传 {@code { reason }} 的形状。
     *
     * <p>{@code reason} <b>必填</b>：退回是把工单往回推，没有原因的退回在时间轴上
     * 就是一次无法解释的空转 —— 既说不清是「派错人」还是「没修好」，
     * 也让被退回的人不知道要改什么。空则 400。
     */
    public record RejectReq(String reason) {
    }

    /**
     * 关单入参。
     *
     * <p>{@code closeReason}：RESOLVED（正常完结）/ INVALID（误报）/ DUPLICATE（重复单）/
     * WITHDRAWN（撤单）。[db-design §9A.4] 用它替代新增 {@code CANCELLED} 态。
     * 前端 {@code WorkOrderClosePayload} 只传验收三件套（{@code auditResult/auditNote/auditorName}），
     * 故 {@code closeReason} 可缺省：{@code PASS/PASS_WITH_ISSUE → RESOLVED} 服务端推导；
     * {@code FAIL} 不允许直接关单（走 {@code /rework}），两者都缺则 400。
     */
    public record CloseReq(String closeReason, String auditResult, String auditNote, String auditorNo) {
    }
}
