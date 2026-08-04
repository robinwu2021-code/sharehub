package ai.neargo.sharehub.platform.notify.service;

import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.SendReq;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.SendResult;

/**
 * 域间发送入口（{@code POST /internal/platform/notify/send}）。
 *
 * <p>它存在的意义就是把「**发送前查黑名单**」这一步收进唯一的通道：
 * 各业务域（订单/告警/风控）都要发通知，若各自直连渠道商，退订就一定会被绕过 —— 而在
 * 短信/邮件合规里，向已退订用户发送是实打实的处罚项。所以本方法是发送的唯一正门。
 */
public interface NotifySendService {

    /** 发送：查黑名单 → （未命中则）投递 → 落 {@code notify_log}（target 脱敏后入库）。 */
    SendResult send(SendReq req);
}
