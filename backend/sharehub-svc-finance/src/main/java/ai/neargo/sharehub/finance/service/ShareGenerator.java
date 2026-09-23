package ai.neargo.sharehub.finance.service;

import ai.neargo.sharehub.api.core.event.OrderSettledEvent;

/**
 * 订单 → 分润明细的生成口。
 *
 * <p>单独立一个接口而不是塞进 {@link ShareService}：那个接口是**运营端的读写面**
 * （规则 CRUD + 明细分页），这个是**系统按事件写账**。两者的调用者、事务边界与
 * 数据范围语义都不同 —— 混在一起迟早有人给写账方法加上 scope 过滤，
 * 那会让代理商名下的订单分不出钱，且不报错。
 */
public interface ShareGenerator {

    /**
     * 为一笔已结算订单生成分润明细。
     *
     * <p><b>幂等</b>：同一单重复调用不会重复记账（约束 {@code uk_srec_order_payee}，V37）。
     * outbox 重投是设计内的，这里必须能安全地被调用两次。
     *
     * @return 实际新增的明细条数；0 表示无可分（无场地方/无代理/无费率）或已生成过
     */
    int generate(OrderSettledEvent e);
}
