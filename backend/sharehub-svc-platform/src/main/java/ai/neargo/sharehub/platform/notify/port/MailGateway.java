package ai.neargo.sharehub.platform.notify.port;

/**
 * 邮件投递端口。两个实现，按 {@code sharehub.mail.stub} 二选一：
 * {@link SmtpMailGateway}（{@code false}，真发）与 {@link StubMailGateway}（缺省 {@code true}，只记日志）。
 *
 * <p>只由 {@code NotifySendServiceImpl} 调用 —— 黑名单、发送记录、脱敏都在那里，
 * 业务代码不得绕过它直接调这个端口。
 */
public interface MailGateway {

    /**
     * 投递一封纯文本邮件。
     *
     * @return 渠道给的 Message-ID（桩返回 null）
     * @throws MailSendException 投递失败（认证、连接、收件方拒收）。<b>不吞</b>：吞掉的话调用方会以为发出去了
     */
    String send(String to, String subject, String body);

    /** 是否真投递。桩时发送记录里如实标出，免得有人看着「已发送」去收件箱找信。 */
    boolean live();

    /** 投递失败。message 不含收件地址与正文（日志规范：不打敏感信息）。 */
    class MailSendException extends RuntimeException {
        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
