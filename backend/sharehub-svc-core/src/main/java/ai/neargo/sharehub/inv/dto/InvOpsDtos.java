package ai.neargo.sharehub.inv.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 调拨作业（逐件明细 · 发货 · 逐件签收 · 资产差异，对齐清单 C4 / C8）的请求与响应。 */
public final class InvOpsDtos {

    private InvOpsDtos() {
    }

    /** 草稿期设定明细（整体替换）：机柜号或充电宝号，按单头 itemType。 */
    public record ItemsReq(List<String> itemNos) {
    }

    /** 签收：现场实收的件号（扫码）。单上有而没收到的记缺件、收到而单上没有的记多件。 */
    public record ReceiveReq(List<String> receivedNos, String note) {
    }

    public record ReceiveResult(String transferNo, String status, int received, List<String> missing, List<String> extra,
                                List<AssetDiff> diffs) {
    }

    public record ResolveReq(String note) {
    }

    public record AssetDiff(String diffNo, String sourceType, String sourceRef, String kind, String itemType, String itemNo,
                            String siteNo, String cabinetNo, Integer expectedQty, Integer actualQty, String status,
                            String resolveNote, String resolvedBy, LocalDateTime resolvedAt, LocalDateTime createdAt) {
    }
}
