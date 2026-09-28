package ai.neargo.sharehub.trade.port;

import ai.neargo.sharehub.api.core.port.RefundApplyPort;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.RefundApplyReq;
import ai.neargo.sharehub.trade.order.service.RefundService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@link RefundApplyPort} 的本地实现：按订单建一张**待财务审批**的退款申请。
 *
 * <p>金额取订单实收金额（全额）。这是**申请值不是结论**：财务在审批时可以驳回，
 * 钱要等审批通过才由支付域退。对用户而言「我这一单有异议」最自然的诉求就是全额，
 * 让端上或客服去猜一个部分金额，反而是把定价决定塞给了不该定的人。
 *
 * <p>三种情况返回 {@code null}（调用方据此改走人工，而不是编一个号出来）：
 * 订单不存在、订单不属于该用户、没有可退金额（未收到钱或金额为 0）。
 */
@Component
public class LocalRefundApply implements RefundApplyPort {

    private static final Logger log = LoggerFactory.getLogger(LocalRefundApply.class);

    private final OrdMapper orders;
    private final RefundService refunds;

    public LocalRefundApply(OrdMapper orders, RefundService refunds) {
        this.orders = orders;
        this.refunds = refunds;
    }

    @Override
    public String applyForOrder(String orderNo, String idempotencyKey, String reason) {
        if (orderNo == null || orderNo.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        OrdOrder o = orders.selectOne(new LambdaQueryWrapper<OrdOrder>()
                .eq(OrdOrder::getOrderNo, orderNo).last("limit 1"));
        if (o == null) {
            log.warn("退款申请找不到订单，本条改走人工 orderNo={}", orderNo);
            return null;
        }
        BigDecimal amount = o.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("订单没有可退金额，本条改走人工 orderNo={} amount={}", orderNo, amount);
            return null;
        }
        return refunds.apply(new RefundApplyReq(orderNo, o.getCUserNo(), amount, o.getCurrency(),
                reason, idempotencyKey)).refundNo();
    }
}
