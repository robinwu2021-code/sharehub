package ai.neargo.sharehub.api.core.event;

import ai.neargo.sharehub.common.event.DomainEvent;


/** 机柜通过上线门禁、进入已布放。站点据此从筹备转营业（E6）。 */
// at 用 ISO-8601 字符串而非 LocalDateTime：Outbox 的序列化器不带 Java 时间模块，
// 与其它事件（OrderSettledEvent 用 period 字符串）同一约定
public record CabinetWentLiveEvent(String cabinetNo, String siteNo, String locationNo, String at)
        implements DomainEvent {

    @Override
    public String aggregateType() {
        return "CABINET";
    }

    @Override
    public String aggregateId() {
        return cabinetNo;
    }

    @Override
    public String eventType() {
        return "CABINET_WENT_LIVE";
    }
}
