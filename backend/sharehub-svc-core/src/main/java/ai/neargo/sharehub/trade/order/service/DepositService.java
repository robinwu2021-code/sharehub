package ai.neargo.sharehub.trade.order.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.DepositRecord;

/**
 * 押金与欠费业务（{@code ord_deposit}）。
 *
 * <p>{@link #release} 手工解冻**只允许从 {@code HELD}** —— {@code BOUGHT_OUT} 的钱已计收入、
 * {@code ARREARS} 的钱待追偿，两者再解冻都是直接的资金漏洞。
 */
public interface DepositService {

    PageResult<DepositRecord> page(Integer page, Integer size, String keyword, String status);

    /** 手工解冻押金。非 HELD 抛异常。 */
    OkResult release(String depositNo);

    /**
     * 押金转买断：用户不还了，押金抵作购机款。
     *
     * <p>充电宝随之转 {@code SOLD} 终态（[db-design §9A.1]）——
     * **买断与丢失(LOST)的财务处理相反**：一个计收入、一个计损失，不能混。
     */
    ai.neargo.sharehub.trade.order.dto.OrderDtos.DepositRecord buyout(String depositNo, String note);

    /**
     * 欠款催缴：记一次催缴动作（次数 + 时间 + 渠道）。
     *
     * <p>**催缴记录是累加的**，不是覆盖 —— 催了几次是判断是否转买断/坏账的依据。
     */
    ai.neargo.sharehub.trade.order.dto.OrderDtos.DepositRecord dun(String depositNo, String channel, String note);
}
