package ai.neargo.sharehub.notify;

import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyLogVO;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.SendReq;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.SendResult;
import ai.neargo.sharehub.platform.notify.entity.NotifyLog;
import ai.neargo.sharehub.platform.notify.port.MailGateway;
import ai.neargo.sharehub.platform.notify.service.NotifyBlacklistService;
import ai.neargo.sharehub.platform.notify.service.NotifyLogService;
import ai.neargo.sharehub.platform.notify.service.impl.NotifySendServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 邮件通道（2026-10-29）：发送服务按投递结果记流水 —— 成功 SENT，失败 FAILED 带原因，桩标 STUB。 */
class MailDeliveryTest {

    private final List<NotifyLog> logged = new ArrayList<>();

    private NotifySendServiceImpl service(MailGateway mail) {
        NotifyBlacklistService bl = mock(NotifyBlacklistService.class);
        when(bl.isBlocked(anyString(), anyString())).thenReturn(false);
        NotifyLogService logs = mock(NotifyLogService.class);
        when(logs.append(any())).thenAnswer(inv -> {
            NotifyLog e = inv.getArgument(0);
            logged.add(e);
            return new NotifyLogVO("NL1", e.getChannel(), null, "m***@x.ae", e.getScene(), e.getSentAt(),
                    e.getStatus(), e.getFailReason(), e.getCost(), null, null, null);
        });
        return new NotifySendServiceImpl(bl, logs, mail);
    }

    private static MailGateway gateway(boolean live, RuntimeException fail, List<String> subjects) {
        return new MailGateway() {
            @Override public String send(String to, String subject, String body) {
                subjects.add(subject);
                if (fail != null) throw fail;
                return "<id@neargo.ai>";
            }
            @Override public boolean live() { return live; }
        };
    }

    @Test
    @DisplayName("★ 真发成功 → SENT，主题按场景定")
    void delivered() {
        List<String> subjects = new ArrayList<>();
        SendResult r = service(gateway(true, null, subjects)).send(
                new SendReq("EMAIL", null, "m@x.ae", "MERCHANT_INVITE", "hi", null, null));
        assertThat(r.status()).isEqualTo("SENT");
        assertThat(subjects).containsExactly("Your ShareHub merchant account");
        assertThat(logged.get(0).getFailReason()).isNull();
    }

    @Test
    @DisplayName("★★ 投递失败 → FAILED 带原因（调用方据此作废验证码、放开重发），不假装成功")
    void failed() {
        SendResult r = service(gateway(true, new MailGateway.MailSendException("SMTP_AUTH_FAILED", null), new ArrayList<>()))
                .send(new SendReq("EMAIL", null, "m@x.ae", "OTP_login", "code 1", null, null));
        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.failReason()).isEqualTo("SMTP_AUTH_FAILED");
    }

    @Test
    @DisplayName("桩模式 → 记 SENT 但标 STUB，免得有人去收件箱找信")
    void stub() {
        service(gateway(false, null, new ArrayList<>())).send(new SendReq("EMAIL", null, "m@x.ae", "OTP_login", "c", null, null));
        assertThat(logged.get(0).getStatus()).isEqualTo("SENT");
        assertThat(logged.get(0).getFailReason()).isEqualTo("STUB");
    }
}
