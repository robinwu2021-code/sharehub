package ai.neargo.sharehub.trade.price.engine;

import ai.neargo.sharehub.trade.price.entity.ScopeLevel;

import java.time.LocalDateTime;

/**
 * 取价入参 —— 一笔订单落在哪里、落在什么设备上（ADR-028 §二）。
 *
 * <p>做成一个记录而不是十个方法参数：层数会增加（ADR 里 LOCATION/DEVICE 是 L2），
 * 每加一层就改一次方法签名，会波及每一个调用方。记录加字段不波及。
 *
 * <p><b>字段可为 null，含义是「这一层无从判断」而不是「不限」。</b>
 * 取不到站点时对应层直接不参与匹配，**不降级成通配** —— 通配会静默命中一个
 * 本不该命中的方案，那正是这次要修的那类错。
 *
 * @param at 取价时刻。用于生效期过滤与时段倍率；下单取「下单时刻」，试算取「指定时刻」
 */
public record PriceQuery(
        String deviceType,
        String deviceNo,
        String locationNo,
        String siteNo,
        String venueNo,
        String agentNo,
        String sceneType,
        String regionId,
        String vendorCode,
        String model,
        String brandNo,
        LocalDateTime at) {

    /** 只知道设备类型时的最小查询（试算与兜底用）。 */
    public static PriceQuery ofDeviceType(String deviceType, LocalDateTime at) {
        return new PriceQuery(deviceType, null, null, null, null, null, null, null, null, null, null, at);
    }

    /** 某一层在本次查询里的引用值；{@code null} = 这一层无从判断，不参与匹配。 */
    public String refOf(ScopeLevel level) {
        return switch (level) {
            case DEVICE -> deviceNo;
            case LOCATION -> locationNo;
            case SITE -> siteNo;
            case VENUE -> venueNo;
            case AGENT -> agentNo;
            case SCENE -> sceneType;
            case REGION -> regionId;
            case ALL -> ScopeLevel.ALL_REF;
        };
    }
}
