package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.api.platform.flow.ApprovalRequestedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ShareHub → OpsKit 的调用口（M5，方案 23 §3.1）。以<b>应用凭证</b>（{@code okk_…}）调用，
 * 提交人经 {@code onBehalfOf} 带上 —— 审批单显示真实提交人，「提交人不能审自己」才判得准。
 *
 * <p>超时刻意短：本类在请求线程的事务提交后被调用（outbox 立即投递），OpsKit 挂了不能把用户的请求拖住；
 * 超时即失败，交给 outbox 退避重投。
 */
@Component
public class OpsKitClient {

    private final RestClient http;
    private final String baseUrl;
    private final String workspace;
    private final String appToken;

    public OpsKitClient(@Value("${sharehub.opskit.base-url:}") String baseUrl,
                        @Value("${sharehub.opskit.workspace:}") String workspace,
                        @Value("${sharehub.opskit.app-token:}") String appToken,
                        @Value("${sharehub.opskit.connect-timeout:2s}") Duration connectTimeout,
                        @Value("${sharehub.opskit.read-timeout:5s}") Duration readTimeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.workspace = workspace;
        this.appToken = appToken;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) connectTimeout.toMillis());
        f.setReadTimeout((int) readTimeout.toMillis());
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /** 三项都配了才算接通；没配时送审一律失败（不静默跳过 —— 那会让业务行永远停在「送审中」） */
    public boolean configured() {
        return !baseUrl.isBlank() && !workspace.isBlank() && !appToken.isBlank();
    }

    public record StartedInstance(String instanceNo, String approvalNo) {
    }

    public record CreatedTicket(String ticketNo, boolean attached) {
    }

    /** 建单（POST /workspaces/{ws}/tickets）；同一幂等键重投拿到同一张；去重键命中未结单时 attached=true */
    @SuppressWarnings("unchecked")
    public CreatedTicket createTicket(ai.neargo.sharehub.api.platform.flow.TicketRequestedEvent e) {
        if (!configured()) {
            throw new IllegalStateException("OpsKit 未配置（SHAREHUB_OPSKIT_BASE_URL / _WS / _APP_TOKEN），无法建单 " + e.bizType() + ":" + e.bizNo());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("typeCode", e.typeCode());
        body.put("title", e.title());
        if (e.description() != null) body.put("description", e.description());
        if (e.priority() != null) body.put("priority", e.priority());
        body.put("fields", e.fields() == null ? Map.of() : e.fields());
        body.put("subject", Map.of("type", e.bizType(), "ref", e.bizNo(), "display", Map.of("title", e.subjectTitle())));
        body.put("scopes", e.scopes() == null ? List.of() : e.scopes());
        if (e.dedupeKey() != null) body.put("dedupeKey", e.dedupeKey());
        if (e.requesterNo() != null) {
            Map<String, Object> behalf = new LinkedHashMap<>();
            behalf.put("externalRef", e.requesterNo());
            behalf.put("displayName", e.requesterName() == null ? e.requesterNo() : e.requesterName());
            body.put("onBehalfOf", behalf);
        }
        Map<String, Object> r;
        try {
            r = http.post()
                    .uri(baseUrl + "/api/opskit/v1/workspaces/{ws}/tickets", workspace)
                    .header("Authorization", "Bearer " + appToken)
                    .header("Idempotency-Key", e.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            String snippet = ex.getResponseBodyAsString();
            throw new IllegalStateException("OpsKit 建单被拒 " + ex.getStatusCode().value() + " "
                    + (snippet.length() > 300 ? snippet.substring(0, 300) : snippet), ex);
        }
        if (r == null || !(r.get("ticketNo") instanceof String ticketNo)) {
            throw new IllegalStateException("OpsKit 建单应答缺 ticketNo：" + r);
        }
        return new CreatedTicket(ticketNo, Boolean.TRUE.equals(r.get("attached")));
    }

    /** 发起审批流程实例（POST /workspaces/{ws}/instances）；同一幂等键重投拿到的是同一个实例 */
    @SuppressWarnings("unchecked")
    public StartedInstance startApproval(ApprovalRequestedEvent e) {
        if (!configured()) {
            throw new IllegalStateException("OpsKit 未配置（SHAREHUB_OPSKIT_BASE_URL / _WS / _APP_TOKEN），无法送审 " + e.bizType() + ":" + e.bizNo());
        }
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("type", e.bizType());
        subject.put("ref", e.bizNo());
        subject.put("display", Map.of("title", e.subjectTitle()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processCode", e.processCode());
        body.put("subject", subject);
        body.put("fields", e.fields() == null ? Map.of() : e.fields());
        body.put("scopes", e.scopes() == null ? List.of() : e.scopes());
        Map<String, Object> behalf = new LinkedHashMap<>();
        behalf.put("externalRef", e.submitterNo());
        behalf.put("displayName", e.submitterName() == null ? e.submitterNo() : e.submitterName());
        if (e.submitterNo() != null) body.put("onBehalfOf", behalf);

        Map<String, Object> r;
        try {
            r = http.post()
                    .uri(baseUrl + "/api/opskit/v1/workspaces/{ws}/instances", workspace)
                    .header("Authorization", "Bearer " + appToken)
                    .header("Idempotency-Key", e.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            // 带上 OpsKit 的错误键与正文开头，排错不用去翻对方日志；不带请求头（含凭证）
            String snippet = ex.getResponseBodyAsString();
            throw new IllegalStateException("OpsKit 送审被拒 " + ex.getStatusCode().value() + " "
                    + (snippet.length() > 300 ? snippet.substring(0, 300) : snippet), ex);
        }
        if (r == null || !(r.get("instanceNo") instanceof String instanceNo)) {
            throw new IllegalStateException("OpsKit 送审应答缺 instanceNo：" + r);
        }
        Object approvalNo = r.get("approvalNo");
        if (approvalNo == null && r.get("approval") instanceof Map<?, ?> a) approvalNo = a.get("approvalNo");
        return new StartedInstance(instanceNo, approvalNo == null ? null : String.valueOf(approvalNo));
    }
}
