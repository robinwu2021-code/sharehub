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
    String createOutbound(String locationId, String purpose, Map<String, String> targets, String remark, List<String> serialNos,
                          String idempotencyKey);

    void postOutbound(String docNo, String idempotencyKey);

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

    // —— 事件 ——

    record Event(long seq, String eventId, String type, String resourceType, String resourceId, int resourceVersion,
                 LocalDateTime occurredAt, String payload) {
    }

    record EventPage(List<Event> events, long lastSeq, long ackedSeq) {
    }

    EventPage pullEvents(Long afterSeq, int limit);

    void ackEvents(long seq);
}
