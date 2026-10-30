package ai.neargo.sharehub.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 深路径的写操作也要留痕（V208）。此前 action 列只有 64 个字符，
 * 待检单逐台质检这类 80 多字符的路径写审计时 Data too long，拦截器吞掉异常只记 ERROR —— 操作全部没有审计。
 */
class AuditLongUriTest extends AuditTestSupport {

    @Test
    @DisplayName("★ 超过 64 字符的路径照样记审计，action 原样保留")
    void long_uri_is_audited() {
        String admin = login("ADMIN");
        String tp = newTraceparent();
        String uri = "/api/ops/inventory/intakes/IK-AUDIT-LONG-URI-0000000000/items/CABINET/CAB-AUDIT-LONG-URI-0000000000/qc";
        assertThat(uri.length() + 5).isGreaterThan(64);
        postWithHeaders(uri, Map.of("powerOn", true, "slotsOk", true), admin, "traceparent", tp);

        JsonNode row = auditOfThisRequest("admin.user", traceIdOf(tp));
        assertThat(row.path("action").asText()).isEqualTo("POST " + uri);
    }
}
