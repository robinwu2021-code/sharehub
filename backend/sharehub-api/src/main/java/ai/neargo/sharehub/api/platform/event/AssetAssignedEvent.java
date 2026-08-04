package ai.neargo.sharehub.api.platform.event;

import ai.neargo.sharehub.common.event.DomainEvent;

import java.util.List;

/**
 * 资产已划拨给代理商 —— platform 发布，core 订阅后回写机柜归属（ADR-019 决策二）。
 *
 * <p><b>{@code locationNos} 必须由事件自带</b>：消费方（core）据此定位要改哪些机柜。
 * 若让它回查 platform 的点位表，就等于把同步调用藏在事件里 —— 单体下看不出问题，
 * 拆分后依然是强耦合，而且比直接调用更难发现。
 *
 * <p>{@code siteNo} 可为 null：按点位划拨时调用方不掌握站点，此时消费方不动冗余的 site_no。
 *
 * @param targetType  SITE / LOCATION / CABINET
 * @param targetNo    划拨标的编号
 * @param agentNo     新归属代理商；空串表示收回平台直营
 * @param siteNo      站点编号，用于同步机柜上的冗余列；可为 null
 * @param locationNos 受影响的点位（消费方按它定位机柜）；CABINET 粒度时为空
 * @param cabinetNo   单台机柜划拨时的机柜号；其余粒度为 null
 */
public record AssetAssignedEvent(String targetType, String targetNo, String agentNo,
                                 String siteNo, List<String> locationNos, String cabinetNo)
        implements DomainEvent {

    @Override
    public String aggregateType() {
        return "AgtAssignment";
    }

    @Override
    public String aggregateId() {
        return targetType + ":" + targetNo;
    }

    @Override
    public String eventType() {
        return "ASSET_ASSIGNED";
    }
}
