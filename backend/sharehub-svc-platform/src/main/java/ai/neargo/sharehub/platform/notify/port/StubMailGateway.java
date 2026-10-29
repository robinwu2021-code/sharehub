package ai.neargo.sharehub.platform.notify.port;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 邮件桩（缺省）：不投递，只记一行日志。本机联调与测试用；dev-mode 下验证码是固定码，不靠收信。
 * 生产要真发：配好 {@code MAIL_*} 并把 {@code SHAREHUB_MAIL_STUB} 设为 false。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sharehub.mail.stub", havingValue = "true", matchIfMissing = true)
public class StubMailGateway implements MailGateway {

    @Override
    public String send(String to, String subject, String body) {
        // 不打收件地址与正文：正文里可能有验证码或一次性口令
        log.info("[mail] 桩模式未投递 subject={}（设 SHAREHUB_MAIL_STUB=false 并配好 MAIL_* 才真发）", subject);
        return null;
    }

    @Override
    public boolean live() {
        return false;
    }
}
