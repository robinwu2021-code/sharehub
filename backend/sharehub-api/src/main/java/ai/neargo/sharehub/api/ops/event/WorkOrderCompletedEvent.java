package ai.neargo.sharehub.api.ops.event;

import ai.neargo.sharehub.common.event.DomainEvent;

/**
 * 工单进入 DONE（完工待验收）。业务告警订阅它做完工复核：关联告警已恢复即自动验收（TDD/05 §7.5）。
 * wo 不认识 alarm，只发事件。
 *
 * <p>设备域也订阅它：装机单完工 → 门禁全过即自动上线（C5）；撤机单完工 → 清点比对、撤机、生成回仓调拨（C8）。
 *
 * @param at         ISO-8601 本地时间（Outbox 序列化器不带 Java 时间模块，与其它事件同一约定）
 * @param locationNo 装机现场扫码的点位（可空）
 * @param countedQty 撤机现场清点的宝数（撤机单必填）
 */
public record WorkOrderCompletedEvent(String woNo, String type, String source, String sourceRef, String siteNo,
                                      String cabinetNo, String at, String locationNo, Integer countedQty) implements DomainEvent {

    /** 兼容构造点（无装机 / 撤机现场数据）。 */
    public WorkOrderCompletedEvent(String woNo, String type, String source, String sourceRef, String siteNo, String cabinetNo, String at) {
        this(woNo, type, source, sourceRef, siteNo, cabinetNo, at, null, null);
    }

    @Override
    public String aggregateType() {
        return "WORK_ORDER";
    }

    @Override
    public String aggregateId() {
        return woNo;
    }

    @Override
    public String eventType() {
        return "WORK_ORDER_COMPLETED";
    }
}
