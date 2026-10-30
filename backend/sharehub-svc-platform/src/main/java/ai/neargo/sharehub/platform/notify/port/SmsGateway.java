package ai.neargo.sharehub.platform.notify.port;

/**
 * 短信投递端口。一期只有桩（{@link StubSmsGateway}，用户 2026-10-29：短信先用 mock）；
 * 接服务商时加一个真实现并按开关二选一，发送服务不用改。只由 {@code NotifySendServiceImpl} 调用。
 */
public interface SmsGateway {

    /**
     * 投递一条短信。
     *
     * @return 渠道给的消息号（桩返回 null）
     * @throws SmsSendException 投递失败；<b>不吞</b>
     */
    String send(String phone, String content);

    /** 是否真投递。桩时发送记录标 STUB。 */
    boolean live();

    /** 投递失败。message 不含号码与正文（日志规范）。 */
    class SmsSendException extends RuntimeException {
        public SmsSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
