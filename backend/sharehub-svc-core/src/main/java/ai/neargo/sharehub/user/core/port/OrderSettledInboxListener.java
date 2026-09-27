package ai.neargo.sharehub.user.core.port;

import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.user.core.service.ConsumerInboxService;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 订单结算 → 站内信（站内信的**第一条真实生产路径**）。
 *
 * <p>结算是最该通知用户的那件事：账单在这一刻才出来。而它已经有事件
 * （{@code OrderSettledEvent}，finance 侧早就订阅着生成分润），所以不必新造事件、
 * 也不必去改 {@code RentOrderServiceImpl} —— 发布点只有一个，订阅方各自加。
 *
 * <p><b>为什么放在 {@code user.core} 而不是 {@code trade}</b>：{@code usr_message} 是
 * user 域的表，而 {@code user.* → trade.*} 这个方向本来就成立
 * （{@code WalletServiceImpl} 读 {@code OrdMapper}）。反过来把消息写在 trade 里
 * 就要 trade 依赖 user，那是包循环，{@code ArchitectureTest} 会红。
 *
 * <p><b>REQUIRES_NEW</b>：本监听器在发布方事务**提交之后**执行
 * （{@code OutboxEventBus} 注册的 afterCommit 回调），此时已无活动事务 ——
 * 同 {@code finance} 侧的 {@code OrderSettledListener}。
 *
 * <p><b>豁免数据范围</b>：系统按事件回查订单，没有「操作者」。被 scope 过滤掉的话
 * 代理商名下的订单查不到，那些用户就永远收不到结算消息 —— 而这不会报错。
 *
 * <p><b>吞异常</b>：这一点与 finance 侧**刻意相反**。分润少一笔是钱，必须回滚重投；
 * 而站内信是附带告知，为它回滚会把已经成立的结算事务连带拖掉。
 * 所以这里失败只记 WARN —— 代价是可能丢一条消息，而那比丢一笔分成轻得多。
 */
@Component
public class OrderSettledInboxListener {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderSettledInboxListener.class);

    private final OrdMapper orders;
    private final ConsumerInboxService inbox;

    public OrderSettledInboxListener(OrdMapper orders, ConsumerInboxService inbox) {
        this.orders = orders;
        this.inbox = inbox;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(OrderSettledEvent e) {
        try {
            /*
             * 事件里没有 cUserNo（它是给 finance 分账用的，字段按那个用途裁的），
             * 所以回查订单。**不给事件加字段** —— 加了就得让 finance 也跟着看一个
             * 与它无关的列，而事件的字段集正是「消费方据此定位」这条约定的产物。
             */
            OrdOrder o = DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                    new LambdaQueryWrapper<OrdOrder>().eq(OrdOrder::getOrderNo, e.orderNo()).last("limit 1")));
            if (o == null) {
                log.warn("结算事件回查不到订单，站内信未发 orderNo={}", e.orderNo());
                return;
            }
            String amount = money(o.getAmount());
            String cur = o.getCurrency() == null ? "" : o.getCurrency();
            String place = o.getLocationName() == null ? "" : o.getLocationName();

            inbox.post(o.getCUserNo(), "ORDER",
                    new ConsumerInboxService.Text("订单已结算", "Order settled", "تمت تسوية الطلب"),
                    new ConsumerInboxService.Text(
                            "订单 " + e.orderNo() + " 已结算，实付 " + amount + " " + cur
                                    + (place.isBlank() ? "" : "（" + place + "）"),
                            "Order " + e.orderNo() + " settled. You paid " + amount + " " + cur
                                    + (place.isBlank() ? "" : " at " + place) + ".",
                            "تمت تسوية الطلب " + e.orderNo() + ". المبلغ المدفوع " + amount + " " + cur
                                    + (place.isBlank() ? "" : " في " + place) + "."));
        } catch (RuntimeException ex) {
            // 见类注释：告知失败不拖垮结算。丢的是一条消息，不是一笔钱
            log.warn("结算站内信发送失败 orderNo={}，结算已生效、仅告知缺失", e.orderNo(), ex);
        }
    }

    /** 金额按两位小数念 —— 「实付 18」与「实付 18.00」在账单上是同一个数，但读起来后者才像钱。 */
    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
