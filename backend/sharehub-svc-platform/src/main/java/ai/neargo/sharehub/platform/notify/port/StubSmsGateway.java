package ai.neargo.sharehub.platform.notify.port;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 短信桩：不投递，只记一行日志（用户 2026-10-29：短信先用 mock）。
 * dev-mode 下验证码是固定码 000000，不靠收短信；生产商家登录以邮箱为主（R1），短信是次要通道。
 * 接服务商时：新增真实现、两者加 {@code @ConditionalOnProperty(sharehub.sms.stub)} 二选一。
 */
@Slf4j
@Component
public class StubSmsGateway implements SmsGateway {

    @Override
    public String send(String phone, String content) {
        // 不打号码与正文：正文里有验证码
        log.info("[sms] 桩模式未投递（短信服务商未接入）");
        return null;
    }

    @Override
    public boolean live() {
        return false;
    }
}
