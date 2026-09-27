package ai.neargo.sharehub.user.core.port;

import ai.neargo.sharehub.api.core.event.OrderArrearsEvent;
import ai.neargo.sharehub.user.core.service.ConsumerInboxService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 欠费告知（2026-09-28）。扣款失败的人<b>下一次借的时候</b>才会被拦 —— 那时他站在柜机前、
 * 急着用电，才第一次知道自己欠钱。结算时就告诉他欠多少、去哪补。
 *
 * <p>告知失败只记 WARN：丢的是一条消息，不是一笔钱（同 {@link OrderSettledInboxListener}）。
 */
@Component
public class OrderArrearsInboxListener {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderArrearsInboxListener.class);

    private final ConsumerInboxService inbox;

    public OrderArrearsInboxListener(ConsumerInboxService inbox) {
        this.inbox = inbox;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(OrderArrearsEvent e) {
        try {
            String amt = (e.amount() == null ? BigDecimal.ZERO : e.amount())
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
            String cur = e.currency() == null ? "" : e.currency();
            inbox.post(e.cUserNo(), "ORDER",
                    new ConsumerInboxService.Text("订单扣款未成功", "Payment didn't go through", "لم تتم عملية الدفع"),
                    new ConsumerInboxService.Text(
                            "订单 " + e.orderNo() + " 应付 " + amt + " " + cur + " 扣款未成功。请在订单详情补缴，补缴后才能借下一单。",
                            "The charge of " + amt + " " + cur + " for order " + e.orderNo()
                                    + " didn't go through. Please pay it in the order details before your next rental.",
                            "لم يتم خصم مبلغ " + amt + " " + cur + " للطلب " + e.orderNo()
                                    + ". يرجى الدفع من تفاصيل الطلب قبل الاستئجار التالي."));
        } catch (RuntimeException ex) {
            log.warn("欠费站内信发送失败 orderNo={}，欠费已记、仅告知缺失", e.orderNo(), ex);
        }
    }
}
