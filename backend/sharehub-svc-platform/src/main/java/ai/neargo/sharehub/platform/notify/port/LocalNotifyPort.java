package ai.neargo.sharehub.platform.notify.port;

import ai.neargo.sharehub.api.platform.port.NotifyPort;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.SendReq;
import ai.neargo.sharehub.platform.notify.service.NotifySendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@link NotifyPort} 的本地实现：经 NotifySendService 走 PUSH 通道（黑名单、落 notify_log 都在那里）。 */
@Component
public class LocalNotifyPort implements NotifyPort {

    private static final Logger log = LoggerFactory.getLogger(LocalNotifyPort.class);

    private final NotifySendService sender;
    private final ai.neargo.sharehub.platform.org.mapper.IamEmployeeMapper employees;

    public LocalNotifyPort(NotifySendService sender, ai.neargo.sharehub.platform.org.mapper.IamEmployeeMapper employees) {
        this.sender = sender;
        this.employees = employees;
    }

    @Override
    public Delivery send(String targetNo, String channel, String scene, String content) {
        if (targetNo == null || targetNo.isBlank()) return new Delivery(false, "NO_TARGET");
        String ch = channel == null ? "PUSH" : channel.trim().toUpperCase();
        if ("PUSH".equals(ch)) {
            push(targetNo, scene, content);
            return new Delivery(true, null);
        }
        if (!"SMS".equals(ch) && !"EMAIL".equals(ch)) return new Delivery(false, "UNSUPPORTED_CHANNEL");
        var e = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> employees.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ai.neargo.sharehub.platform.org.entity.IamEmployee>()
                        .eq(ai.neargo.sharehub.platform.org.entity.IamEmployee::getEmployeeNo, targetNo).last("limit 1")));
        // 代理商等非员工：联系方式在别处（代理档案 / PII 库），这条通道还没接 —— 如实返回，由调用方落 FAILED 流水
        if (e == null) return new Delivery(false, "NOT_EMPLOYEE");
        String to = "SMS".equals(ch) ? e.getPhone() : e.getEmail();
        if (to == null || to.isBlank()) return new Delivery(false, "NO_CONTACT");
        // 员工表按设计只存掩码（明文在 PII 库）；拿到掩码值就发不出去，别把掩码当号码发
        if (to.contains("*")) return new Delivery(false, "MASKED_ONLY");
        try {
            var r = sender.send(new SendReq(ch, null, to, scene, content, null, null));
            return r.blocked() ? new Delivery(false, "BLACKLISTED") : new Delivery(true, null);
        } catch (RuntimeException ex) {
            // 不打目标：手机号 / 邮箱属敏感信息，员工号足以定位
            log.warn("运营通知投递失败 employeeNo={} channel={} scene={}，业务已生效、仅通知缺失", targetNo, ch, scene, ex);
            return new Delivery(false, "SEND_ERROR");
        }
    }

    @Override
    public void push(String targetNo, String scene, String content) {
        if (targetNo == null || targetNo.isBlank()) return;
        try {
            sender.send(new SendReq("PUSH", null, targetNo, scene, content, null, null));
        } catch (RuntimeException e) {
            // 通知失败不回滚业务：撤单、开告警本身已经成立，通知是附带的
            log.warn("运营通知投递失败 target={} scene={}，业务已生效、仅通知缺失", targetNo, scene, e);
        }
    }
}
