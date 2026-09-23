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

    /**
     * 事件类型常量。
     *
     * <p><b>放在声明方而不是 common 里的枚举</b>：{@code sys_outbox.event_type} 是
     * **开放注册**，各域自己声明自己的事件类型。做成 common 的枚举等于让
     * {@code sharehub-common} 反向依赖每一个业务域 —— 那正是 arch-guard G4
     * （common 零业务依赖）禁止的形状。
     *
     * <p>消费方（如 {@code OwnershipReconciler}）引用这个常量而不是抄一遍字符串，
     * 改名时编译器就会指出所有引用点。
     */
    public static final String TYPE = "ASSET_ASSIGNED";

    @Override
    public String eventType() {
        return TYPE;
    }
}
