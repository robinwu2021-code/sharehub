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

    /** 现场处理入参，镜像前端 {@code WorkOrderHandlePayload}（+ 照片/换件标记）。 */
    public record HandleReq(String assigneeNo, String photos, String handleNote,
                            Boolean partChanged, Boolean deviceChanged) {
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
