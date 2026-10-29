package ai.neargo.sharehub.inv.remote;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 独立进销存（ai-shop backend/inventory，ADR-046）的 HTTP 契约 —— 只含 ShareHub 用到的部分（S1.1）。
 *
 * <p>写操作一律带幂等键：同键同体重放返回原结果，同键异体 409。实现：{@link HttpInventoryApi}；
 * 场景测试用内存 Fake（S1.2）。错误统一抛 {@link InventoryRemoteException}，带库存服务的错误码与参数。
 */
public interface InventoryApi {

    boolean configured();

    // —— 主数据 ——

    /** 物料按本系统编码 upsert，返回库存服务 itemId。 */
    String upsertItem(String externalRef, ItemSpec spec);

    record ItemSpec(String name, String baseUom, String trackingMode, String gatePolicy, String gateRefType, String attributes) {
    }

    /** 外部目标（MERCHANT / SITE / PERSON）upsert；有父类型的须带 parentRef。 */
    void upsertTarget(String type, String ref, String name, String parentRef);

    List<Warehouse> warehouses();

    record Warehouse(String locationId, String name, String regionRef, String status) {
    }

    String createWarehouse(String name, String idempotencyKey);

    // —— 入库 ——

    record Serial(String serialNo, String gateRef, String parentSerialNo) {
    }

    record InboundLine(String itemId, Integer qty, String gateRef, List<Serial> serials) {
    }

    /** 建入库草稿（purpose：PURCHASE / RETURN），返回单号。 */
    String createInbound(String locationId, String purpose, String sourceRef, String remark, List<InboundLine> lines, String idempotencyKey);

    void postInbound(String docNo, String idempotencyKey);

    /** 退货入库后业务侧已解除本地关联：释放 RETURN_PENDING 冻结。 */
    void businessClearance(String docNo, String idempotencyKey);

    // —— 出库与出库池 ——

    /** 建出库草稿（purpose：ISSUE 进出库池），返回单号。targets：MERCHANT / SITE / PERSON。 */
    default String createOutbound(String locationId, String purpose, Map<String, String> targets, String remark, List<String> serialNos,
                                  String idempotencyKey) {
        return createOutbound(locationId, purpose, null, targets, remark, List.of(), serialNos, idempotencyKey);
    }

    /** 数量物料的出库行（物料单据，V210）。 */
    record OutboundLine(String itemId, Integer qty, String parentSerialNo) {
    }

    /**
     * 建出库草稿（通用）：reasonCode 报废时必填；targets 退供应商时为 {@code SUPPLIER → 供应商号}；
     * lines 为数量物料，serialNos 为逐件物料（可同单混装）。
     */
    String createOutbound(String locationId, String purpose, String reasonCode, Map<String, String> targets, String remark,
                          List<OutboundLine> lines, List<String> serialNos, String idempotencyKey);

    void postOutbound(String docNo, String idempotencyKey);

    // —— 余额与在库序列号（物料库存，V210）——

    record Balance(String itemId, String locationId, BigDecimal onHand, BigDecimal reserved, BigDecimal unavailable, BigDecimal available) {
    }

    /** 余额：locationId / itemId 都可空（空 = 不限）。 */
    List<Balance> balances(String locationId, String itemId);

    record InStock(String serialNo, String itemId, String locationId) {
    }

    /** 在库序列号。 */
    List<InStock> serials(String itemId, String locationId);

    record DispatchView(String dispatchId, String outboundNo, String itemId, String serialNo, BigDecimal qty, String locationId,
                        String status, String parentDispatchId, String rootTargetType, String rootTargetRef, int resourceVersion) {
    }

    /** 按序列号查出库池记录（任何状态）。 */
    List<DispatchView> dispatchesBySerial(String serialNo);

    record Claim(String claimNo, List<String> dispatchIds) {
    }

    Claim claim(List<String> dispatchIds, String businessRef, String idempotencyKey);

    void releaseClaim(String claimNo, String idempotencyKey);

    void redirect(String dispatchId, Map<String, String> targets, String reason, String idempotencyKey);

    void revoke(String dispatchId, String reason, String idempotencyKey);

    // —— 流水与盘点（S4）——

    record LedgerEntry(Long id, String itemId, String locationId, String docKind, String docNo, Integer lineNo, String reasonCode,
                       BigDecimal qtyDelta, BigDecimal balanceAfter, LocalDateTime occurredAt, String operator) {
    }

    /** 库存流水，按 id 升序；afterId 翻页。 */
    List<LedgerEntry> ledger(String itemId, String locationId, String docNo, Long afterId, int limit);

    record CountLine(String itemId, String trackingMode, int bookQty, Integer countedQty, Integer diffQty) {
    }

    record CountScan(String serialNo, String verdict) {
    }

    record CountView(String countNo, String locationId, String status, int contentVersion, Integer approvedVersion, String submittedBy,
                     String reviewer, List<CountLine> lines, List<CountScan> exceptions, List<String> missingSerials) {
    }

    record CountPosted(String gainInboundNo, String lossOutboundNo) {
    }

    String openCount(String locationId, List<String> itemIds, String idempotencyKey);

    CountView count(String countNo);

    List<CountScan> scanCount(String countNo, List<String> serialNos, String idempotencyKey);

    void submitCount(String countNo, String idempotencyKey);

    void approveCount(String countNo, String note, String idempotencyKey);

    void rejectCount(String countNo, String note, String idempotencyKey);

    CountPosted postCount(String countNo, String idempotencyKey);

    void cancelCount(String countNo, String idempotencyKey);

    record PeriodRow(String locationId, String itemId, BigDecimal opening, Map<String, BigDecimal> inByReason,
                     Map<String, BigDecimal> outByReason, BigDecimal closing) {
    }

    /** 期间汇总（S6）：期初、按原因的入 / 出、期末。 */
    List<PeriodRow> periodSummary(LocalDateTime from, LocalDateTime to, String locationId);

    // —— 事件 ——

    record Event(long seq, String eventId, String type, String resourceType, String resourceId, int resourceVersion,
                 LocalDateTime occurredAt, String payload) {
    }

    record EventPage(List<Event> events, long lastSeq, long ackedSeq) {
    }

    EventPage pullEvents(Long afterSeq, int limit);

    void ackEvents(long seq);
}
