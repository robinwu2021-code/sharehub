package ai.neargo.sharehub.api.platform.event;

import ai.neargo.sharehub.common.event.DomainEvent;

/**
 * 站点撤场关闭（对齐清单 C9）。finance 据此按最后一份合同生成结算调整项（押金退还 / 进场费结清）。
 *
 * @param closedAt ISO-8601 本地时间（Outbox 序列化器不带 Java 时间模块）
 */
public record SiteClosedEvent(String siteNo, String venueNo, String closedAt) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "LocSite";
    }

    @Override
    public String aggregateId() {
        return siteNo;
    }

    @Override
    public String eventType() {
        return "SITE_CLOSED";
    }
}
