package ai.neargo.sharehub.api.platform.event;

import ai.neargo.sharehub.common.event.DomainEvent;

/**
 * 进场合同已签（ADR-027 §四 / TDD-A2 第 4 批）。
 *
 * <p>牵线（{@code REFER}）的对价是「把关系介绍过来」这个**一次性动作**，
 * 按逐单比例付会变成「介绍一次、分十年」。所以它不走逐单分润，而由本事件触发一次性结算。
 *
 * <p><b>触发点选签约而不是首单结算</b>：合同签了钱就该付，不该让介绍人等第一个顾客 ——
 * 那个顾客什么时候来，牵线人既不能控制也看不见。
 *
 * <p>字段自带消费方所需的全部信息（见 {@link DomainEvent}）：finance 拿到后不必回查 platform。
 *
 * @param contractNo 合同业务键。它同时充当分润记录的 {@code order_no} ——
 *                   这一笔的来源单据就是合同本身，而不是某一张订单
 * @param siteNo     合同绑定的站点；牵线责任行挂在「伙伴 × 站点」上
 * @param venueNo    场地方编号，仅作排错时的溯源
 * @param currency   合同币种；一次性对价按合同的币种付
 */
public record ContractSignedEvent(String contractNo, String siteNo, String venueNo, String currency)
        implements DomainEvent {

    @Override
    public String aggregateType() {
        return "LocContract";
    }

    @Override
    public String aggregateId() {
        return contractNo;
    }

    @Override
    public String eventType() {
        return "CONTRACT_SIGNED";
    }
}
