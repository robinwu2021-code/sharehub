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
    @DisplayName("★★ 改站点的营业时间，审计查得出从什么改成了什么")
    void changing_a_site_operator_is_recorded() {
        // 2026-09-25 站点状态机：归属代理不再经站点编辑改（只经划拨），编辑接口收 SiteReq。
        // 营业时间决定离线告警是否计时 —— 「谁把营业时间改短了、以致夜里断电没报警」同样要查得到。
        // 自建一个筹备中站点来改，测完关闭并归档（筹备中无设备可直接关闭），不碰种子站点。
        String admin = login("ADMIN");
        Map<String, Object> body = new HashMap<>();
        body.put("name", "审计测试站点");
        body.put("venueNo", get("/api/ops/venues?page=1&size=1", admin).okData().path("list").get(0).path("venueNo").asText());
        body.put("regionId", "R-AUDIT");
        body.put("openHours", "10:00-22:00");
        String siteNo = post("/api/ops/sites", body, admin).okData().path("siteNo").asText();

        String tp = newTraceparent();
        try {
            body.put("openHours", "08:00-20:00");
            postWithHeaders("/api/ops/sites/" + siteNo, body, admin, "traceparent", tp).okData();

            JsonNode row = auditOfThisRequest("admin.user", traceIdOf(tp));
            JsonNode change = findChange(changesOf(row), "营业时间");
            assertThat(change.path("before").asText()).isEqualTo("10:00-22:00");
            assertThat(change.path("after").asText()).isEqualTo("08:00-20:00");
        } finally {
            post("/api/ops/sites/" + siteNo + "/close", Map.of("note", "审计测试结束"), admin).okData();
            post("/api/ops/sites/" + siteNo + "/archive", Map.of(), admin).okData();
        }
    }

    @Test
    @DisplayName("★★ 机柜是部分更新：只传了型号，就只该有型号一条 diff")
    void a_partial_cabinet_update_only_records_what_was_sent() {
        // 机柜按 containsKey 逐键更新。若在 setter 前逐个判 containsKey 来记 diff，
        // 等于把那段逻辑抄一遍——抄错就会把「没传的键」记成「被改成了 null」，
        // 而那是一条**看起来确凿、实际没发生**的改动。取快照再比就不会。
        // 2026-09-25：状态不再经编辑接口改（机柜状态机），改用型号验证同一件事。
        String admin = login("ADMIN");
        JsonNode before = get("/api/ops/cabinets/CAB1001", admin).okData();
        String original = before.path("cabinet").path("model").asText(null);
        String other = "AUDIT-MODEL-X".equals(original) ? "AUDIT-MODEL-Y" : "AUDIT-MODEL-X";

        String tp = newTraceparent();
        try {
            postWithHeaders("/api/ops/cabinets/CAB1001", Map.of("model", other), admin, "traceparent", tp)
                    .okData();

            List<String> fields = changesOf(auditOfThisRequest("admin.user", traceIdOf(tp)))
                    .findValuesAsText("field");
            assertThat(fields)
                    .as("只传了 model，其余字段一个都不该出现在 diff 里")
                    .containsExactly("型号");
        } finally {
            Map<String, Object> restore = new HashMap<>();
            restore.put("model", original);
            postWithHeaders("/api/ops/cabinets/CAB1001", restore, admin, "traceparent", newTraceparent()).okData();
        }
    }

    // ——————————————————————— 脚手架 ———————————————————————

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
