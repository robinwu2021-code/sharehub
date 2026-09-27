package ai.neargo.sharehub.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.core.event.DeviceSignalEvent;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * trade 侧订阅设备信号，推进「出宝中」的订单（{@code DISPENSING → IN_USE}）或撤掉出宝失败的单。
 *
 * <p><b>这是借出链路上一直缺的那一环。</b>改造前 {@code rent()} 直接把订单写成 IN_USE，
 * 设备回没回执与订单无关 —— 宝没弹出来，订单照样开始计费，而系统里一处异常都没有。
 * 试借还（{@code TrialRentServiceImpl#onSignal}）早就是按回执推进的，借出没接上。
 *
 * <p><b>为什么订阅事件而不是新开一个内网端点</b>：设备信号进来后
 * {@code DeviceSignalService} 已经发了 {@link DeviceSignalEvent}（业务告警、试借还都订阅它），
 * 再加一条 HTTP 端点等于让网关多认识一个协议，而且两条路会有各自的幂等与重试。
 *
 * <p><b>REQUIRES_NEW</b>：同 {@code OrderSettledListener} —— 监听器在发布方提交之后执行，
 * 此时已无活动事务。<b>豁免数据范围</b>：这里是系统按事件写订单，没有「操作者」，
 * 被 scope 过滤会让回执静默落空（订单查不到 → 当成没有单 → 五分钟后被撤单）。
 */
@Component
public class RentDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(RentDeliveryListener.class);

    /**
     * 出宝成功的物理事实：仓门弹出。只认字典码 —— 驱动的 {@code EJECT_OK} 在信号入口已译成它
     * （{@code DeviceSignalServiceImpl#DRIVER_ALIASES}），这里不再两个名字都认。
     */
    private static final Set<String> DELIVERED = Set.of("SLOT_EJECT_OK");
    /** 出宝失败：柜机自己报的弹仓超时。 */
    private static final Set<String> FAILED = Set.of("EJECT_TIMEOUT");
    /** 指令回执：成败在 {@code attrs.result}。 */
    private static final String COMMAND_RESULT = "COMMAND_RESULT";

    private final RentOrderService orders;

    public RentDeliveryListener(RentOrderService orders) {
        this.orders = orders;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(DeviceSignalEvent s) {
        String code = s.code();
        if (code == null) return;
        boolean delivered = DELIVERED.contains(code);
        boolean failed = FAILED.contains(code);
        if (COMMAND_RESULT.equals(code)) {
            // 只认这条指令回执里明确的成败；其它 result 值（或没有）一律不动订单 ——
            // 猜一个结论出来的代价是「把没出宝的单开成计费中」
            String result = s.attr("result");
            delivered = "OK".equals(result);
            failed = "FAIL".equals(result);
        }
        if (!delivered && !failed) return;

        String orderNo = DataScopeContext.executeWithoutScope(
                () -> orders.pendingDeliveryOrder(s.cabinetNo(), s.slotIndex()));
        if (orderNo == null) {
            // 常态，不是错：试借还、运营手动弹仓、以及本来就没有单在等的回执都会走到这里
            log.debug("设备回执无对应的出宝中订单 code={} cabinetNo={} slot={}", code, s.cabinetNo(), s.slotIndex());
            return;
        }
        if (delivered) {
            orders.confirmDelivered(orderNo, "DEVICE:" + code);
        } else {
            // 撤单不收费。预授权的释放要等支付通道接入（见 cancelUndelivered 里的注释），
            // 所以这里**不要**对用户宣称「已解冻」——C 端失败页的文案也是照这个口径写的
            boolean done = orders.cancelUndelivered(orderNo, "设备回报出宝失败（" + code + "）");
            log.warn("出宝失败撤单 orderNo={} cabinetNo={} slot={} code={} done={} —— 若 done=false，"
                    + "多半是超时自愈已先一步处理", orderNo, s.cabinetNo(), s.slotIndex(), code, done);
        }
    }
}
