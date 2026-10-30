package ai.neargo.sharehub.platform.notify.port;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * SMTP 邮件（Office 365）。照 ai-shop {@code SmtpMailGateway} 的做法与踩过的坑：
 *
 * <ul>
 *   <li><b>发件地址要等于认证账号</b>，除非 M365 后台授了「发送为」—— 否则认证成功而投递被拒
 *       （554 SendAsDenied）。不一致时启动即 WARN；</li>
 *   <li>邮箱开了 MFA 时普通密码不可用，要用应用密码；</li>
 *   <li>EHLO 与 Message-ID 的域都用发件域 —— 不设会报本机私网 IP，信被扔进垃圾箱，而链路上每步都显示成功；</li>
 *   <li>连接 / 读 / 写三个超时都设：调用方同步等待，通道不可达时不能把线程挂住；</li>
 *   <li>开了通道却缺配置 → <b>启动失败</b>，不退回桩：退回桩会让「邀请邮件已发送」照常出现，而商家永远收不到。</li>
 * </ul>
 *
 * <p>用 jakarta.mail 而不是 JavaMailSender：只需要 Session + Transport，且离线构建要求依赖在本地仓。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sharehub.mail.stub", havingValue = "false")
public class SmtpMailGateway implements MailGateway {

    private final Session session;
    private final String from;

    public SmtpMailGateway(@Value("${sharehub.mail.host:smtp.office365.com}") String host,
                           @Value("${sharehub.mail.port:587}") int port,
                           @Value("${sharehub.mail.username:}") String username,
                           @Value("${sharehub.mail.password:}") String password,
                           @Value("${sharehub.mail.from:}") String from,
                           @Value("${sharehub.mail.protocols:TLSv1.2}") String protocols) {
        require(username, "MAIL_USERNAME");
        require(password, "MAIL_PASSWORD (use an app password if the mailbox has MFA)");
        this.from = from == null || from.isBlank() ? username : from;
        if (!this.from.equalsIgnoreCase(username)) {
            log.warn("[mail] 发件地址与认证账号不一致 —— Office 365 需在后台授「发送为」权限，"
                    + "否则认证成功而投递被拒（554 SendAsDenied）。请运维确认授权或把 MAIL_FROM 改成认证账号");
        }

        Properties p = new Properties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
        p.put("mail.smtp.ssl.protocols", protocols);
        p.put("mail.smtp.localhost", domainOf(this.from));   // EHLO 用发件域
        p.put("mail.from", this.from);                       // Message-ID 的域也随它
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        this.session = Session.getInstance(p, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
        log.info("[mail] SMTP 已启用 {}:{}", host, port);
    }

    @Override
    public String send(String to, String subject, String body) {
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            msg.setSubject(subject, StandardCharsets.UTF_8.name());
            // 纯文本：验证码与口令不需要排版，HTML 邮件更容易被判成垃圾邮件
            msg.setText(body, StandardCharsets.UTF_8.name());
            msg.saveChanges();
            Transport.send(msg);
            return msg.getMessageID();
        } catch (jakarta.mail.AuthenticationFailedException e) {
            throw new MailSendException("SMTP_AUTH_FAILED", e);
        } catch (Exception e) {
            throw new MailSendException("SEND_ERROR", e);
        }
    }

    @Override
    public boolean live() {
        return true;
    }

    /** 只连接与认证、不发信 —— 本机核对凭据用（{@code MailProbe}）。 */
    public void probe() throws jakarta.mail.MessagingException {
        try (Transport t = session.getTransport("smtp")) {
            t.connect();
        }
    }

    private static void require(String v, String env) {
        if (v == null || v.isBlank()) {
            // 启动期配置错误：让应用起不来，而不是退回桩（退回桩会让「已发送」照常出现，而收件人永远收不到）
            throw new IllegalArgumentException("Mail channel enabled (sharehub.mail.stub=false) but missing " + env);
        }
    }

    private static String domainOf(String address) {
        int at = address == null ? -1 : address.indexOf('@');
        return at > 0 && at < address.length() - 1 ? address.substring(at + 1) : "localhost";
    }
}
