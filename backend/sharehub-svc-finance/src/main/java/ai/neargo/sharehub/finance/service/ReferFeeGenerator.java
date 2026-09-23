package ai.neargo.sharehub.finance.service;

import ai.neargo.sharehub.api.platform.event.ContractSignedEvent;

/**
 * 合同签约 → 一次性牵线费（ADR-027 §四 / TDD-A2 第 4 批）。
 *
 * <p>与 {@link ShareGenerator} 的区别只有一处，但那一处是本质的：
 * <b>牵线费不随订单走</b>。它的对价是「把关系介绍过来」这个一次性动作，
 * 按逐单比例付会变成「介绍一次、分十年」。
 */
public interface ReferFeeGenerator {

    /**
     * 为该合同的站点上所有「牵线」责任各结一笔一次性费用。
     *
     * @return 实际生成的分润记录条数；重复投递时为 0（唯一键挡住，不报错也不重复付）
     */
    int generate(ContractSignedEvent e);
}
