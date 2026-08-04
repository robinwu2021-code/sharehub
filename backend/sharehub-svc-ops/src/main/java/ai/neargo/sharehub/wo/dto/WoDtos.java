package ai.neargo.sharehub.wo.dto;

/**
 * 工单域 DTO。
 *
 * <p>自骨架期共享集合 {@code dto.Dto} 归位 —— DTO 属于它描述的那个域，
 * 留在顶层会让 svc-ops 被迫依赖 app。
 */
public final class WoDtos {

    private WoDtos() {
    }

    /**
     * 工单行，镜像前端 {@code WorkOrder}（workorder.ts，G6 闭环 15 个流转留痕字段）。
     *
     * <p>留痕字段的取数来源（富装配在 {@code WoOpsServiceImpl.pageRich}）：
     * {@code sourceNo/expectedAt/rejectReason/rejectCount/auditor*} ← {@code wo_order} 扩展列；
     * {@code dispatchedAt/acceptedAt} ← {@code wo_dispatch} 时间轴；
     * {@code handler*}/{@code completedAt} ← {@code wo_handle} 时间轴。
     * {@code partsReplaced}：库里只有 {@code part_changed} 布尔位，无自由文本列——
     * 有换件时出 {@code "PART_CHANGED"} 标记值、无则 null，**不编造描述文案**。
     */
    public record WorkOrder(String woNo, String type, String source, String priority, String cabinetNo,
                           String locationName, String status, String assigneeName, String slaDueAt,
                           String description, String createdAt,
                           String sourceNo, String expectedAt,
                           String dispatchedAt, String acceptedAt,
                           String handlerName, String handledAt, String handleNote, String partsReplaced,
                           String completedAt,
                           String auditorName, String auditedAt, String auditResult, String auditNote,
                           String rejectReason, Integer rejectCount) {

        /** 兼容旧 11 参调用（动作端点回包/历史代码），留痕字段缺省 null。 */
        public WorkOrder(String woNo, String type, String source, String priority, String cabinetNo,
                         String locationName, String status, String assigneeName, String slaDueAt,
                         String description, String createdAt) {
            this(woNo, type, source, priority, cabinetNo, locationName, status, assigneeName, slaDueAt,
                    description, createdAt, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
    }
}
