package ai.neargo.sharehub.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改前改后快照接入的其余资源（T3-2 的「增量启用」）。
 *
 * <h2>为什么这里只挑两条用例，而不是六个资源各测一遍</h2>
 * 六处接入里只有<b>三种不同的机制</b>：新旧两个对象（分润规则、合同、数据范围 ——
 * 已由 {@link AuditFieldChangesTest} 覆盖）、<b>就地 set</b>（站点、代理商、站点责任）、
 * <b>部分更新</b>（机柜）。后两种各有各的出错方式，各测一条；
 * 同机制的资源再堆用例只是把同一件事测六遍。
 */
class AuditChangesCoverageTest extends AuditTestSupport {

    @Test
    @DisplayName("★★ 改站点的归属代理，审计查得出从谁改成了谁")
    void changing_a_site_operator_is_recorded() {
        // 这条对应一个真实的排查：ST300 从某代理改成平台直营后，
        // 原代理仍看得见那个站点的历史订单（数据范围锚点没跟着更新）。
        // 当时想查「谁在什么时候改的」——库里没有答案。现在有了。
        String admin = login("ADMIN");
        // 没有单条读站点的端点，而列表的 keyword **只匹配名称/场地方/区域，不匹配编号**
        // （LocService.pageSites）——照抄场地方那边的写法会一条都查不到。翻页找。
        JsonNode site = findSite(admin, "ST301");
        String original = site.path("agentNo").asText();

        String tp = newTraceparent();
        try {
            postWithHeaders("/api/ops/sites/ST301", siteBody(site, "AG003"), admin, "traceparent", tp).okData();

            JsonNode row = auditOfThisRequest("admin.user", traceIdOf(tp));
            JsonNode change = findChange(changesOf(row), "归属代理");
            assertThat(change.path("before").asText()).isEqualTo(original);
            assertThat(change.path("after").asText()).isEqualTo("AG003");
        } finally {
            postWithHeaders("/api/ops/sites/ST301", siteBody(site, original), admin,
                    "traceparent", newTraceparent()).okData();
        }
    }

    @Test
    @DisplayName("★★ 机柜是部分更新：只传了状态，就只该有状态一条 diff")
    void a_partial_cabinet_update_only_records_what_was_sent() {
        // 机柜按 containsKey 逐键更新。若在 setter 前逐个判 containsKey 来记 diff，
        // 等于把那段逻辑抄一遍——抄错就会把「没传的键」记成「被改成了 null」，
        // 而那是一条**看起来确凿、实际没发生**的改动。取快照再比就不会。
        String admin = login("ADMIN");
        JsonNode before = get("/api/ops/cabinets/CAB1001", admin).okData();
        String original = before.path("status").asText();
        String other = "MAINTENANCE".equals(original) ? "DEPLOYED" : "MAINTENANCE";

        String tp = newTraceparent();
        try {
            postWithHeaders("/api/ops/cabinets/CAB1001", Map.of("status", other), admin, "traceparent", tp)
                    .okData();

            List<String> fields = changesOf(auditOfThisRequest("admin.user", traceIdOf(tp)))
                    .findValuesAsText("field");
            assertThat(fields)
                    .as("只传了 status，其余字段一个都不该出现在 diff 里")
                    .containsExactly("状态");
        } finally {
            postWithHeaders("/api/ops/cabinets/CAB1001", Map.of("status", original), admin,
                    "traceparent", newTraceparent()).okData();
        }
    }

    // ——————————————————————— 脚手架 ———————————————————————

    private JsonNode findSite(String admin, String siteNo) {
        for (int page = 1; page <= 50; page++) {
            JsonNode body = get("/api/ops/sites?page=" + page + "&size=200", admin).okData();
            for (JsonNode s : body.path("list")) {
                if (siteNo.equals(s.path("siteNo").asText())) return s;
            }
            if ((long) page * 200 >= body.path("total").asLong()) break;
        }
        throw new AssertionError("站点不存在: " + siteNo);
    }

    /** 照抄读回来的站点，只换归属代理 —— 保存是整体覆盖，少传字段会把它们清空。 */
    private static Map<String, Object> siteBody(JsonNode site, String agentNo) {
        Map<String, Object> b = new HashMap<>();
        b.put("siteNo", site.path("siteNo").asText());
        b.put("name", site.path("name").asText());
        b.put("venueNo", site.path("venueNo").asText(null));
        b.put("agentNo", agentNo);
        b.put("address", site.path("address").asText(null));
        b.put("status", site.path("status").asText());
        return b;
    }

    private static JsonNode changesOf(JsonNode auditRow) {
        JsonNode detail = auditRow.path("detail");
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(detail.isTextual() ? detail.asText() : detail.toString())
                    .path("changes");
        } catch (Exception e) {
            throw new AssertionError("detail 不是 JSON：" + detail, e);
        }
    }

    private static JsonNode findChange(JsonNode changes, String field) {
        for (JsonNode c : changes) {
            if (field.equals(c.path("field").asText())) return c;
        }
        throw new AssertionError("diff 里没有「" + field + "」：" + changes);
    }
}
