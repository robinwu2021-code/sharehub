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

    /**
     * 直接发给一个**联系方式**（不是业务号）—— C 端验证码这类「收件人还不是系统里的人」的场景。
     *
     * <p>{@link #send} 解决不了这个：它按 {@code targetNo} 去 {@code iam_employee} 查联系方式，
     * 非员工返回 {@code NOT_EMPLOYEE}。而 OTP 的收件人是一个**尚未注册**的手机号。
     *
     * <p>仍然走 {@code NotifySendService} 这道唯一正门 —— 黑名单校验、{@code notify_log} 留痕、
     * 目标脱敏入库都在那里。<b>绕过它就等于绕过退订</b>，而向已退订号码发送在短信合规里是处罚项。
     *
     * @param target  明文联系方式（手机号 / 邮箱）；入库前由发送侧脱敏
     * @param channel SMS / EMAIL / PUSH
     */
    Delivery sendToTarget(String target, String channel, String scene, String content);

    record Delivery(boolean sent, String reason) {
    }
}
