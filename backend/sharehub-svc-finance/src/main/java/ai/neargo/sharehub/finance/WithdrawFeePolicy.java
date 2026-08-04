package ai.neargo.sharehub.finance;

import java.math.BigDecimal;

/**
 * 提现手续费口径（策略接口）。
 *
 * <p><b>真实来源是 {@code sys_biz_rule(category='WITHDRAW')}</b>（[db-design §5.5]
 * 「手续费口径来自 sys_biz_rule(WITHDRAW)」）—— 该表属 platform 子域配置，
 * 本子域<b>刻意不直接读它</b>：财务算费只依赖这个接口，规则表怎么存、几档阶梯、
 * 是否按币种分档，都由实现方消化。这样做的收益是提现审批逻辑不会被配置表结构变更牵连。
 *
 * <p>骨架期由 {@link StubWithdrawFeePolicy} 顶上（读硬编码默认值）；platform 域的
 * {@code sys_biz_rule} 服务就位后，写一个读该表的 {@code @Primary} 实现即可完成切换，
 * 财务域一行不改。
 */
public interface WithdrawFeePolicy {

    /**
     * 计算某笔提现的手续费。
     *
     * @param amount    提现申请额（必须 &gt; 0）
     * @param currency  币种（多国开城后同一档规则在不同币种下阈值不同）
     * @param payeeType VENUE / AGENT —— 场地方与代理商可以是两档费率
     * @return 手续费，与 {@code amount} 同币种；实际到账 = {@code amount - fee}
     */
    BigDecimal feeOf(BigDecimal amount, String currency, String payeeType);
}
