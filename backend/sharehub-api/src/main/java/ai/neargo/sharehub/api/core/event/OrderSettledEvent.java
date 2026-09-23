package ai.neargo.sharehub.api.core.event;

import ai.neargo.sharehub.common.event.DomainEvent;

import java.math.BigDecimal;

/**
 * 订单已结算 —— core（交易）发布，finance 订阅后生成分润明细（`share_record`）。
 *
 * <p><b>为什么要有它</b>：在此之前，订单结算完就没有下文了 —— `share_record` 只有演示
 * 数据在写，`SettlementServiceImpl` 只读不产，于是「订单产生了收入」与「谁该分到钱」
 * 之间**没有任何代码连着**，结算单永远是空的。这是 MVP 三条硬阻塞里唯一没有外部依赖的一条。
 *
 * <p><b>为什么走事件而不是直接调用</b>：trade 在 svc-core、分润在 svc-finance，
 * 两个模块互不依赖（都只依赖 common + api）。事件是它们之间既有的、也是唯一的通路
 * （同 {@code AssetAssignedEvent}）。
 *
 * <p><b>字段为什么是这几个</b>：消费方据此定位「哪个站点的哪一单、多少钱」。
 * 站点与代理由发布方解析后带上 —— core 自己就持有 {@code dev_cabinet} 的归属冗余列，
 * 让 finance 回查 core 等于把同步调用藏在事件里（见 {@link DomainEvent} 的约定）。
 * 场地方与合同费率不在这里：那是 platform 的数据，发布方没有，由消费方经
 * {@code SiteSharingQueryPort} 取 —— 那是一次**诚实的**跨服务读，不是藏起来的。
 *
 * @param orderNo     订单业务键
 * @param cabinetNo   借出机柜；排错时按它追溯归属链
 * @param siteNo      借出站点；**为空表示归属链断了**（机柜没绑点位/站点），消费方据此跳过并告警
 * @param agentNo     归属代理；空 = 平台直营，此时不产生 AGENT 维度的分润
 * @param grossAmount 分润基数（本单实收）。**不是应收** —— 免单与券抵扣的部分没有真实现金流，
 *                    按它分账等于用平台的钱替用户给场地方付分成
 * @param currency    币种，随单快照
 * @param period      归属结算周期 {@code YYYY-MM}，由发布方按结算时刻定格
 */
public record OrderSettledEvent(String orderNo, String cabinetNo, String siteNo, String agentNo,
                                BigDecimal grossAmount, String currency, String period)
        implements DomainEvent {

    @Override
    public String aggregateType() {
        return "OrdOrder";
    }

    @Override
    public String aggregateId() {
        return orderNo;
    }

    @Override
    public String eventType() {
        return "ORDER_SETTLED";
    }
}
