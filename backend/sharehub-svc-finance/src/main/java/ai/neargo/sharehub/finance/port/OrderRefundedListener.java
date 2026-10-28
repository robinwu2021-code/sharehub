package ai.neargo.sharehub.finance.port;

import ai.neargo.sharehub.api.core.event.OrderRefundedEvent;
import ai.neargo.sharehub.finance.service.impl.ShareReversal;
import ai.neargo.common.data.scope.DataScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * finance 侧订阅「退款已批准」，冲回该单的分成（V170）。
 *
 * <p>事务、数据范围与异常处理同 {@link OrderSettledListener}：发布方提交后执行故 REQUIRES_NEW；
 * 系统按事件记账没有操作者故豁免范围；失败不吞，由 outbox 重投 —— 吞掉的话这笔退款的分成
 * 就永远不会被冲回，商家多拿的钱在结算日之前没人看得见。
 */
@Component
public class OrderRefundedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderRefundedListener.class);

    private final ShareReversal reversal;

    public OrderRefundedListener(ShareReversal reversal) {
        this.reversal = reversal;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(OrderRefundedEvent e) {
        int n = DataScopeContext.executeWithoutScope(() -> reversal.reverse(e));
        log.info("退款 {} 批准 → 订单 {} 冲回分成 {} 条（退款 {} {}）", e.refundNo(), e.orderNo(), n, e.amount(), e.currency());
    }
}
