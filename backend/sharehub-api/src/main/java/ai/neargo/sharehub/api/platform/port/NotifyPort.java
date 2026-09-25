package ai.neargo.sharehub.api.platform.port;

/**
 * 站内推送（platform.notify 实现）。告警、工单撤单等运营通知走它，不在各域里写占位通知行。
 * <b>通知失败不影响业务</b>：实现吞掉投递异常并记 WARN，调用方不必 try。
 */
public interface NotifyPort {

    /**
     * @param targetNo 接收人业务号（员工号 / 代理号）
     * @param scene    业务场景码（如 ALARM_OPENED / WO_WITHDRAWN）
     */
    void push(String targetNo, String scene, String content);

    /**
     * 按渠道发给某个人（批次 E4）：{@code PUSH} 同 {@link #push}；{@code SMS} / {@code EMAIL} 按员工档案里的联系方式发，
     * 投递流水（notify_log）里的目标一律脱敏。
     *
     * @return 投递结果；{@code sent=false} 时 {@code reason} 说明原因（无联系方式 / 仅有掩码 / 黑名单 / 不支持的渠道 / 非员工）
     */
    Delivery send(String targetNo, String channel, String scene, String content);

    record Delivery(boolean sent, String reason) {
    }
}
