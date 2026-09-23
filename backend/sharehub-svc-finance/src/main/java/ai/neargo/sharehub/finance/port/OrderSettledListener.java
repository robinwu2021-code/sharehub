package ai.neargo.sharehub.finance.port;

import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.finance.service.ShareGenerator;
import ai.neargo.common.data.scope.DataScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * finance 侧订阅「订单已结算」，生成分润明细。
 *
 * <p><b>REQUIRES_NEW</b>：本监听器在发布方事务**提交之后**执行
 * （{@code OutboxEventBus} 注册的 afterCommit 回调），此时已无活动事务 ——
 * 同 {@code AssetAssignedListener}。
 *
 * <p><b>豁免数据范围</b>：这里是系统按事件写账，没有「操作者」。若被 scope 过滤，
 * 代理商名下订单的分润会查不到规则而静默算不出来 —— 一笔钱凭空消失且不报错。
 *
 * <p><b>不吞异常</b>：生成失败让事务回滚、由 outbox 重投。吞掉的话这一单的分成就
 * 永远不会再有人去补 —— 而「少了一笔分成」在结算日之前没有任何人看得见。
 */
@Component
public class OrderSettledListener {

    private static final Logger log = LoggerFactory.getLogger(OrderSettledListener.class);

    private final ShareGenerator generator;

    public OrderSettledListener(ShareGenerator generator) {
        this.generator = generator;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(OrderSettledEvent e) {
        int n = DataScopeContext.executeWithoutScope(() -> generator.generate(e));
        log.info("订单 {} 结算 → 生成分润明细 {} 条（站点 {} 代理 {} 基数 {} {}）",
                e.orderNo(), n, e.siteNo(), e.agentNo(), e.grossAmount(), e.currency());
    }
}
