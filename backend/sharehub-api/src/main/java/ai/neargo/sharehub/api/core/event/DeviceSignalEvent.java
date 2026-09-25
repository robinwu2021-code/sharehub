package ai.neargo.sharehub.api.core.event;

import ai.neargo.sharehub.common.event.DomainEvent;

import java.util.Map;

/**
 * 归一化后的设备信号（厂商错误码经驱动映射到 {@code dev_event_code}）。
 * 设备域先按字典执行保护动作，再发本事件；业务告警（ops）与试借还订阅它。
 *
 * @param signalNo   上游事件号（网关 eventId），也是消费端去重键
 * @param occurredAt ISO-8601 本地时间字符串（Outbox 序列化器不带 Java 时间模块，与其它事件同一约定）
 */
public record DeviceSignalEvent(String signalNo, String code, String cabinetNo, Integer slotIndex, String powerbankNo,
                                String siteNo, String agentNo, String vendorCode, String vendorErrorCode,
                                String occurredAt, Map<String, String> attrs) implements DomainEvent {

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
        return "DEVICE_SIGNAL";
    }

    public java.time.LocalDateTime occurredAtTime() {
        return occurredAt == null ? null : java.time.LocalDateTime.parse(occurredAt);
    }

    public String attr(String key) {
        return attrs == null ? null : attrs.get(key);
    }
}
