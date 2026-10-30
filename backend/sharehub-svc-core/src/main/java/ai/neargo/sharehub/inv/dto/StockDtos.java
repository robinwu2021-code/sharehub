package ai.neargo.sharehub.inv.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 库存流水与盘点（S4）。本地 / 独立进销存两个引擎同一形状。 */
public final class StockDtos {

    private StockDtos() {
    }

    /** 当前库存引擎：LOCAL / SERVICE；SERVICE 时 configured 表示凭证已配。 */
    /**
     * 库存引擎与连通性（开发计划 M0 0.3）。endpointMode：STATIC 固定地址 / DISCOVERY 服务发现；
     * reachable：SERVICE 且已配置时现探一次（读仓库列表），LOCAL 或未配置为 null；failure：不可达时对方错误码。
     * pendingOperations：未完成（待查回 / 待复核）的库存操作数，任何引擎都报 —— 不为 0 时不能切换引擎（F09）。
     */
    public record InventoryEngine(String engine, boolean configured, String endpointMode, Boolean reachable, String failure,
                                  long pendingOperations) {
    }

    /** 一条库存流水：qtyDelta 进仓 +、出仓 −；docKind 本地是履历事件、进销存是单据类型。 */
    public record StockLedgerRow(String id, String warehouseNo, String itemType, String itemNo, String docKind, String docNo,
                                 int qtyDelta, LocalDateTime occurredAt, String operator) {
    }

    public record StockCount(String countNo, String warehouseNo, String status, LocalDateTime openedAt) {
    }

    public record StockCountLine(String itemType, int bookQty, Integer countedQty, Integer diffQty) {
    }

    public record StockCountScan(String serialNo, String verdict) {
    }

    public record CountReview(String state,String approvalNo,int round,String submitterNo,String decision,String deciderName,String lastError,boolean configured) {}

    public record StockCountDetail(StockCount count, List<StockCountLine> lines, List<StockCountScan> exceptions,
                                   List<String> missingSerials,CountReview review) {
        public StockCountDetail(StockCount count,List<StockCountLine> lines,List<StockCountScan> exceptions,List<String> missingSerials) {
            this(count,lines,exceptions,missingSerials,null);
        }
    }

    /**
     * 统一库存视图（S6）：按生命周期阶段的台数。阶段码：INTAKE 到仓待检 · IN_STOCK 在仓 · IN_TRANSIT 在途 ·
     * DISPATCHED 已发货待签收 · ISSUED 已交付待绑定 · BOUND 已绑定待上线 · LIVE 投放中（宝：在柜）· RENTED 借出 ·
     * FAULT 故障 · LOST 遗失 · RETIRED 已离开（报废 / 售出）。
     *
     * <p>source（对接明细 B02）：SERVICE 取自库存服务（独立进销存引擎下的「在仓」）、LOCAL 取自本地业务表、
     * SERVICE_UNAVAILABLE 库存服务此刻查不到（台数为空，不当 0）。asOf 是取数时间。
     */
    public record StockStage(String stage, Long cabinets, Long powerbanks, String source, java.time.LocalDateTime asOf) {
    }

    /**
     * 阶段下钻的一台设备。model：柜为型号、宝为厂商码；agentNo：出库池商家或绑定商家；
     * locationRef：柜为点位号、宝为所在柜号；intakeNo 仅 INTAKE 阶段有。
     */
    public record StageItem(String itemType, String itemNo, String model, String warehouseNo, String agentNo, String siteNo,
                            String locationRef, String intakeNo) {
    }

    /**
     * 设备库存余额一行（对接明细 B01）：库存服务里某仓、某设备类型的数量。
     * onHand 在手 = reserved 占用 + unavailable 不可用（冻结、待检等）+ available 可用。
     */
    public record DeviceBalance(String warehouseNo, String warehouseName, String itemType, long onHand, long reserved, long unavailable,
                                long available) {
    }

    /** 库存服务里在库的一个设备序列号（余额下钻）。 */
    public record DeviceSerial(String serialNo, String itemType, String warehouseNo) {
    }

    /** total 为该阶段全量台数；items 最多 2000 台（前端分组、筛选）。 */
    public record StageItems(String stage, String itemType, long total, java.util.List<StageItem> items) {
    }

    /** 期间报表一行（S6）：期初 + 入 − 出 = 期末；入 / 出按原因（本地为履历事件，进销存为单据原因）。 */
    public record StockPeriodRow(String warehouseNo, String itemType, long opening, long inQty, long outQty, long closing,
                                 java.util.Map<String, Long> inByReason, java.util.Map<String, Long> outByReason) {
    }

    /** 存量迁移一批（S7）：最多 limit 台，缺省 200。 */
    public record MigrationRunReq(Integer limit) {
    }

    public record OpenCountReq(String warehouseNo) {
    }

    public record CountScanReq(List<String> serialNos) {
    }

    public record CountNoteReq(String note) {
    }
}
