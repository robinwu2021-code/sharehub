package ai.neargo.sharehub.platform.notify;

import java.util.Arrays;
import java.util.Optional;

/**
 * 一条通知发出去了没有（{@code notify_log.status}，词表见 V5 的列注释 {@code SENT/FAILED}）。
 *
 * <h3>只有两档，而且「被拦下」也算一档</h3>
 * 被黑名单拦下的记 {@link #FAILED} + {@code fail_reason=BLACKLISTED}，
 * 不是「不记」—— <b>「没发出去」和「从没尝试过」是两回事</b>：
 * 前者要能在发送记录页查到，否则运营会把退订造成的触达衰减误判成渠道故障
 * （见 {@code NotifySendServiceImpl} 类注释）。
 *
 * <h3>为什么收进枚举</h3>
 * 这两个值此前散在四处裸字符串里，其中两处是<b>统计口径</b>
 * （今日发送量 {@code eq("status","SENT")} / 失败率 {@code eq("status","FAILED")}）。
 * 统计口径打错一个字母的表现是<b>数字变成 0</b>，而 0 看起来像「今天没发」，
 * 不像「查错了」—— 没有任何一侧会报错。
 */
public enum NotifyLogStatus {

    /** 已交给渠道商。渠道回执改写状态与 {@code cost} 由接入层负责。 */
    SENT,
    /** 没发出去。原因写 {@code fail_reason}（渠道返回，或 {@code BLACKLISTED}）。 */
    FAILED;

    public static Optional<NotifyLogStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
