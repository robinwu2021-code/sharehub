package ai.neargo.sharehub.inv.remote;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** externalRef：库位在本系统的编号（= 本地仓号）。库存服务 60a614575 起支持，旧版返回空。 */
    record Warehouse(String locationId, String name, String regionRef, String status, String externalRef) {
        public Warehouse(String locationId, String name, String regionRef, String status) {
            this(locationId, name, regionRef, status, null);
        }
    }

    default String createWarehouse(String name, String idempotencyKey) {
        return createWarehouse(name, null, idempotencyKey);
    }

    /** 建仓并带本系统编码（对接方案 §4.2：映射按 externalRef，不按名称）；旧版库存服务忽略 externalRef。 */
    String createWarehouse(String name, String externalRef, String idempotencyKey);

    /** 改仓库名称 / 区域（regionRef 可空）。 */
    void updateWarehouse(String locationId, String name, String regionRef, String idempotencyKey);

    /** 停用仓库：库存服务拒绝有库存的仓。 */
    void disableWarehouse(String locationId, String idempotencyKey);

    // —— 入库 ——

    record Serial(String serialNo, String gateRef, String parentSerialNo) {
    }

    record InboundLine(String itemId, Integer qty, String gateRef, List<Serial> serials) {
    }

    /** 建入库草稿（不带依据与附件），返回单号。 */
    default String createInbound(String locationId, String purpose, String sourceRef, String remark, List<InboundLine> lines,
                                 String idempotencyKey) {
        return createInbound(locationId, purpose, sourceRef, null, List.of(), remark, lines, idempotencyKey);
    }

    /**
     * 建入库草稿，带依据与附件：业态档案里要求依据 / 附件的用途（生产档案 sharehub.json 的 OPENING 两样都要）
     * 不带会被拒 {@code BAD_REQUEST basis / attachmentRefs}。basis 是 ≤ 32 字符的依据编号（库存服务列宽），不是说明文字。
     */
    String createInbound(String locationId, String purpose, String sourceRef, String basis, List<String> attachmentRefs, String remark,
                         List<InboundLine> lines, String idempotencyKey);

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

    /** 出库池查询条件，各项都可空（空 = 不限）；列表项之间是「或」。 */
    record DispatchFilter(List<String> statuses, List<String> rootTargetRefs, String targetType, List<String> targetRefs,
                          String outboundNo, String serialNo, String itemId) {
        public static DispatchFilter bySerial(String serialNo) {
            return new DispatchFilter(null, null, null, null, null, serialNo, null);
        }
    }

    /** 出库池多条件查询（按商家、状态、出库单、序列号、物料）。 */
    List<DispatchView> dispatchPool(DispatchFilter filter);

    /** 按序列号查出库池记录（任何状态）。 */
    default List<DispatchView> dispatchesBySerial(String serialNo) {
        return dispatchPool(DispatchFilter.bySerial(serialNo));
    }

    record InTransit(String rootTargetType, String rootTargetRef, String itemId, BigDecimal qty) {
    }

    /** 已出库未退回的数量，按去向与物料汇总。这是出库池的「在途」，不是仓间调拨在途。 */
    List<InTransit> inTransit();

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

    /**
     * 盘点单。status 用宿主词表：OPEN / SUBMITTED / APPROVED / POSTED / CANCELLED ——
     * 库存服务的 COUNTING、VOIDED 在 {@link HttpInventoryApi} 里换成 OPEN、CANCELLED，调用方与页面只认宿主词表。
     */
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

    /** 数量物料实盘：录入（或改）某物料的实盘数，只能在盘点单 OPEN 时。 */
    void countLine(String countNo, String itemId, int countedQty, String idempotencyKey);

    record PeriodRow(String locationId, String itemId, BigDecimal opening, Map<String, BigDecimal> inByReason,
                     Map<String, BigDecimal> outByReason, BigDecimal closing) {
    }

    /** 期间汇总（S6）：期初、按原因的入 / 出、期末。 */
    List<PeriodRow> periodSummary(LocalDateTime from, LocalDateTime to, String locationId);

    // —— 单据详情与查回（恢复用）——

    record DocLine(int lineNo, String itemId, BigDecimal qty, String gateRef, String parentSerialNo) {
    }

    record DocSerial(int lineNo, String itemId, String serialNo, String parentSerialNo, String gateRef) {
    }

    record DocTarget(String type, String ref, String nameSnapshot) {
    }

    record Document(String docNo, String kind, String status, String purpose, String locationId, BigDecimal totalQty,
                    Integer profileVersion, List<DocLine> lines, List<DocSerial> serials, List<DocTarget> targets) {
    }

    Document inbound(String docNo);

    Document outbound(String docNo);

    /** 库存服务记下的写命令：resultBody 是当时的响应 JSON。 */
    record StoredCommand(String commandType, String resultBody, LocalDateTime createdAt) {
    }

    /**
     * 按幂等键（requestId）查写命令结果。库存服务没有这条记录时为空 ——
     * 空只说明「此刻没有已提交的结果」，原请求可能仍在途，不能据此判定没执行，由调用方按命令协议处理。
     */
    Optional<StoredCommand> command(String requestId);

    /** 按领取请求的幂等键查领取结果；为空的含义同 {@link #command}。 */
    Optional<Claim> claimByRequest(String requestId);

    // —— 事件 ——

    record Event(long seq, String eventId, String type, String resourceType, String resourceId, int resourceVersion,
                 LocalDateTime occurredAt, String payload) {
    }

    record EventPage(List<Event> events, long lastSeq, long ackedSeq) {
    }

    EventPage pullEvents(Long afterSeq, int limit);

    void ackEvents(long seq);
}
