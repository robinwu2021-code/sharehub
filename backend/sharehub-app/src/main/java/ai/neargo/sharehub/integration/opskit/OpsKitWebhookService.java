package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.api.platform.flow.ApprovalOutcomeHandler.ApprovalDecision;
import ai.neargo.sharehub.common.event.dedup.EventIdempotency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * OpsKit → ShareHub 的事件处理（M5，方案 23 §3.2）。验签在控制器里做完，这里只管「去重 + 路由 + 推进」。
 *
 * <p><b>去重与业务同事务</b>（{@link EventIdempotency} 的约定）：业务推进抛异常，去重记录一起回滚，
 * 控制器回 500，OpsKit 按退避重投 —— 不会出现「记了已处理、业务却没动」。
 */
@Service
public class OpsKitWebhookService {

    private static final Logger log = LoggerFactory.getLogger(OpsKitWebhookService.class);
    static final String HANDLER = "OpsKitWebhook";
    /** sys_event_consumed.event_no 是 VARCHAR(36) */
    private static final int KEY_MAX = 36;

    private final EventIdempotency once;
    private final ApprovalOutcomeHandlers handlers;

    public OpsKitWebhookService(EventIdempotency once, ApprovalOutcomeHandlers handlers) {
        this.once = once;
        this.handlers = handlers;
    }

    /** @return true = 本次真的处理了；false = 重复到达或不关心的事件 */
    @Transactional
    public boolean handle(String eventType, String idemKey, Map<String, Object> data) {
        if (!"approval.completed".equals(eventType)) {
            // instance.* / task.* / sla.* 目前不需要回写（业务行只关心审批结论）；订阅面收窄前先忽略
            log.debug("OpsKit 事件不需要回写，忽略 type={} idemKey={}", eventType, idemKey);
            return false;
        }
        if (idemKey == null || idemKey.isBlank()) {
            throw new IllegalArgumentException("OpsKit event has no idemKey; cannot dedupe");   // 只进日志与 500，不到界面
        }
        Map<?, ?> subject = data.get("subject") instanceof Map<?, ?> m ? m : Map.of();
        String bizType = str(subject.get("type"));
        String bizNo = str(subject.get("ref"));
        var handler = handlers.find(bizType);
        if (handler.isEmpty() || bizNo == null) {
            // 不是 ShareHub 送的审批（如直接在 OpsKit 里发起的），或 OpsKit 还没带 subject：记下来但不重投
            log.warn("OpsKit 审批结束但找不到对应业务，未回写 type={} ref={} approvalNo={}（若是本系统送审的单，核对 subject 与回写实现）",
                    bizType, bizNo, data.get("approvalNo"));
            return false;
        }
        ApprovalDecision d = decision(data);
        return once.once(dedupKey(idemKey), HANDLER, eventType, () -> handler.get().onCompleted(bizNo, d));
    }

    static ApprovalDecision decision(Map<String, Object> data) {
        Object by = data.get("decidedBy");
        String no = null;
        String name = null;
        if (by instanceof Map<?, ?> m) {
            no = str(m.get("externalRef"));
            name = str(m.get("displayName"));
        }
        if (no == null) no = str(data.get("decidedByExternalRef"));
        if (name == null) name = str(data.get("decidedByName"));
        return new ApprovalDecision(str(data.get("result")), str(data.get("reason")), no, name, str(data.get("approvalNo")));
    }

    /** 超长的键取摘要：OpsKit 的键形如 approvalNo:roundNo，通常 30 字以内 */
    static String dedupKey(String idemKey) {
        String k = "okk:" + idemKey;
        if (k.length() <= KEY_MAX) return k;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(idemKey.getBytes(StandardCharsets.UTF_8));
            return "okh:" + HexFormat.of().formatHex(h).substring(0, KEY_MAX - 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
