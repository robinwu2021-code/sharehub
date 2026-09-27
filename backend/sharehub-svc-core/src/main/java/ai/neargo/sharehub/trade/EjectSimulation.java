package ai.neargo.sharehub.trade;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 没有真实柜机时，**模拟出宝回执**（{@code sharehub.trade.simulate-eject.enabled}，默认 true）。
 *
 * <p>借出的真实链路是：下单 → {@code DISPENSING} → 柜机弹仓 → 设备回执
 * （{@code EJECT_OK}）→ {@code IN_USE}。而现在接入的驱动只有 {@code DemoPowerbankDriver}，
 * 它的 {@code send} 只打一行日志、**不会回任何事件** —— 真按这条路走，
 * 演示环境里每一单都会停在「出宝中」，五分钟后被 {@code RENT_NOT_DELIVERED}
 * 自愈撤单。于是留这个开关：开着时下单后立刻自己补一次回执。
 *
 * <p><b>与「直接把订单置成 IN_USE」的区别</b>（那正是改造前的写法）：
 * <ul>
 *   <li>订单**真的经过** DISPENSING，状态机、时间线、C 端的等待与超时兜底、
 *       出宝失败告警，走的都是与真机同一条路；</li>
 *   <li>补回执用的是与设备回执**同一个方法**（{@code confirmDelivered}），
 *       不是另写一条捷径 —— 接真机那天只是这个开关关掉，代码一行不改；</li>
 *   <li>它有名字、有启动横幅。改造前那句「弹仓为骨架」只写在注释里，
 *       而注释不会在启动日志里提醒任何人。</li>
 * </ul>
 *
 * <p>照 {@code StubPaymentPort} 的做法打 WARN 横幅：模拟成功的东西必须在日志里显形，
 * 否则它会被一路带到生产而没人发现。
 */
@Component
public class EjectSimulation {

    private static final Logger log = LoggerFactory.getLogger(EjectSimulation.class);

    private final boolean enabled;

    public EjectSimulation(@Value("${sharehub.trade.simulate-eject.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    @PostConstruct
    void warnIfEnabled() {
        if (enabled) {
            log.warn("""
                    ============================================================
                     出宝回执为**模拟**（sharehub.trade.simulate-eject.enabled=true）
                     下单后立刻自行补一次 EJECT_OK —— 宝是否真的弹出，系统并不知道。
                     接入真实柜机驱动后请置 false，改由设备回执推进 DISPENSING→IN_USE。
                    ============================================================""");
        }
    }
}
