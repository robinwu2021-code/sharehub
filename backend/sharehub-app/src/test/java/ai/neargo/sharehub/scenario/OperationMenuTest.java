package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营管理菜单的后端（清单 OM-S1 / S2 / S3 / S4 / S5 / S6）。
 *
 * <p>这一批端点此前**全部不存在** —— 前端契约写好了、页面做完了，菜单里却只能灰着，
 * 因为 `lib/backend-ready.ts` 如实登记着「后端未实现」。本测试是把那些标志翻成 true 的依据。
 *
 * <p>断言走真实 HTTP，因为要验的正是「运营端那一页能不能用」，
 * 而不是「service 方法返回了什么」—— 少一个 @PreAuthorize 或路径写错，service 测试照样绿。
 */
class OperationMenuTest extends ApiTestSupport {

    /** 启用中的方案。调价只对启用方案生效（停用的方案改了也没人用）。 */
    private JsonNode activePlan(String admin) {
        for (JsonNode p : get("/api/trade/price-plans?page=1&size=50", admin).okData().path("list")) {
            if ("ACTIVE".equals(p.path("status").asText())) return p;
        }
        throw new AssertionError("前置：至少要有一个启用中的收费方案");
    }

    @Test
    void overview_answers_scale_business_and_attention() {
        String admin = login("ADMIN");
        JsonNode d = get("/api/ops/operation/overview", admin).okData();

        // 规模：站点/机柜的存量，不随时间筛选变化
        assertThat(d.path("scale").path("siteTotal").asInt()).isPositive();
        assertThat(d.path("scale").path("siteActive").asInt() + d.path("scale").path("sitePaused").asInt())
                .as("营业中 + 暂停 应等于总数（归档的不计入）")
                .isEqualTo(d.path("scale").path("siteTotal").asInt());
        assertThat(d.path("scale").path("onlineRate").asDouble()).isBetween(0.0, 1.0);

        // 趋势必须**补齐没有订单的那些天**：不补的话折线会把相隔六天的两点画成相邻
        assertThat(d.path("trend").size()).as("默认窗口 7 天").isEqualTo(7);

        assertThat(d.path("geoReady").path("total").asInt())
                .isEqualTo(d.path("scale").path("siteTotal").asInt());
        assertThat(d.path("ranking").isArray()).isTrue();
        assertThat(d.path("attention").isArray()).isTrue();
        // 待关注每条都要带得出具体说明，否则运营不知道先处理哪个
        for (JsonNode a : d.path("attention")) {
            assertThat(a.path("detail").asText()).isNotBlank();
            assertThat(a.path("severity").asText()).isIn("high", "medium", "low");
        }
    }

    @Test
    void site_stats_splits_by_point() {
        String admin = login("ADMIN");
        String siteNo = get("/api/ops/sites?page=1&size=1", admin).okData().path("list").get(0).path("siteNo").asText();

        JsonNode d = get("/api/ops/sites/" + siteNo + "/stats", admin).okData();
        assertThat(d.path("siteNo").asText()).isEqualTo(siteNo);
        assertThat(d.path("trend").size()).isEqualTo(7);
        assertThat(d.path("byPoint").isArray()).isTrue();
        assertThat(d.path("onlineRate").asDouble()).isBetween(0.0, 1.0);
    }

    @Test
    void stats_of_unknown_site_is_rejected_not_empty() {
        // 查一个不存在的站点应当报错，而不是返回一份全 0 的统计 ——
        // 全 0 会被读成「这个站点没有生意」，而真相是「没有这个站点」
        assertThat(get("/api/ops/sites/NO_SUCH_SITE/stats", login("ADMIN")).status).isEqualTo(400);
    }

    @Test
    void pause_and_resume_site() {
        String admin = login("ADMIN");
        // **必须挑一个营业中的站点**：种子里有 PAUSED 的，盲取 list[0] 会让用例随种子顺序时好时坏
        String siteNo = null;
        for (JsonNode s : get("/api/ops/sites?page=1&size=50", admin).okData().path("list")) {
            if ("ACTIVE".equals(s.path("status").asText())) { siteNo = s.path("siteNo").asText(); break; }
        }
        assertThat(siteNo).as("前置：至少要有一个营业中的站点").isNotNull();

        // 原因必填：没有原因的停业，事后没人说得清为什么停
        assertThat(post("/api/ops/sites/" + siteNo + "/pause", Map.of("reason", ""), admin).status).isEqualTo(400);

        assertThat(post("/api/ops/sites/" + siteNo + "/pause", Map.of("reason", "商场装修"), admin)
                .okData().path("status").asText()).isEqualTo("PAUSED");
        // 重复暂停要拒绝，而不是静默成功 —— 静默成功会让人以为自己点错了按钮
        assertThat(post("/api/ops/sites/" + siteNo + "/pause", Map.of("reason", "商场装修"), admin).status)
                .isEqualTo(400);

        assertThat(post("/api/ops/sites/" + siteNo + "/resume", Map.of(), admin)
                .okData().path("status").asText()).isEqualTo("ACTIVE");
        assertThat(post("/api/ops/sites/" + siteNo + "/resume", Map.of(), admin).status).isEqualTo(400);
    }

    @Test
    void price_adjustment_applies_and_reverts() {
        String admin = login("ADMIN");
        JsonNode plan = activePlan(admin);
        String planNo = plan.path("planNo").asText();
        double before = plan.path("unitPrice").asDouble();
        double target = before + 1;

        // 生效时间取过去：save 之后列表的惰性 tick 会立刻把它执行掉
        Map<String, Object> body = new HashMap<>();
        body.put("planNo", planNo);
        body.put("name", "测试调价");
        body.put("patch", Map.of("unitPrice", target));
        body.put("effectiveAt", LocalDate.now().minusDays(1) + "T00:00:00Z");
        body.put("reason", "自动化测试");
        String adjustNo = post("/api/trade/price-adjustments", body, admin).okData().path("adjustNo").asText();

        JsonNode listed = get("/api/trade/price-adjustments?page=1&size=50&keyword=" + adjustNo, admin)
                .okData().path("list").get(0);
        assertThat(listed.path("status").asText()).as("到点的调价应在列表打开时就已生效").isEqualTo("APPLIED");
        // 生效那一刻要把原值快照下来，否则恢复时只能反推，会抹掉别人的改动
        assertThat(listed.path("beforeSnapshot").path("unitPrice").asDouble()).isEqualTo(before);
        assertThat(get("/api/trade/price-plans?page=1&size=50&keyword=" + planNo, admin)
                .okData().path("list").get(0).path("unitPrice").asDouble())
                .as("方案的单价应已被改成调价值").isEqualTo(target);

        // 已生效的不能撤销，只能提前恢复
        assertThat(post("/api/trade/price-adjustments/" + adjustNo + "/cancel",
                Map.of("reason", "不想要了"), admin).status).isEqualTo(400);

        assertThat(post("/api/trade/price-adjustments/" + adjustNo + "/revert", Map.of(), admin)
                .okData().path("status").asText()).isEqualTo("REVERTED");
        assertThat(get("/api/trade/price-plans?page=1&size=50&keyword=" + planNo, admin)
                .okData().path("list").get(0).path("unitPrice").asDouble())
                .as("恢复后单价应回到原值").isEqualTo(before);
    }

    @Test
    void adjustment_refuses_to_revert_over_a_manual_edit() {
        String admin = login("ADMIN");
        JsonNode plan = activePlan(admin);
        String planNo = plan.path("planNo").asText();
        double before = plan.path("unitPrice").asDouble();

        Map<String, Object> body = new HashMap<>();
        body.put("planNo", planNo);
        body.put("name", "会被人工改掉的调价");
        body.put("patch", Map.of("unitPrice", before + 2));
        body.put("effectiveAt", LocalDate.now().minusDays(1) + "T00:00:00Z");
        String adjustNo = post("/api/trade/price-adjustments", body, admin).okData().path("adjustNo").asText();
        get("/api/trade/price-adjustments?page=1&size=50&keyword=" + adjustNo, admin);   // 触发生效

        // 调价期间有人手工把方案改成了别的值
        Map<String, Object> edit = new HashMap<>();
        edit.put("planNo", planNo);
        edit.put("name", plan.path("name").asText());
        edit.put("unitPrice", before + 9);
        post("/api/trade/price-plans/" + planNo, edit, admin).okData();

        /*
         * 此时恢复必须**拒绝**并置 FAILED：按快照写回会把那个人的改动悄悄抹掉。
         * 悄悄覆盖比不恢复更难查 —— 没有人会知道发生过。
         */
        JsonNode r = post("/api/trade/price-adjustments/" + adjustNo + "/revert", Map.of(), admin).okData();
        assertThat(r.path("status").asText()).isEqualTo("FAILED");
        assertThat(r.path("failReason").asText()).contains("人工修改");
        assertThat(get("/api/trade/price-plans?page=1&size=50&keyword=" + planNo, admin)
                .okData().path("list").get(0).path("unitPrice").asDouble())
                .as("别人的改动必须原封不动").isEqualTo(before + 9);

        // 收拾现场：把方案改回去，避免污染其它用例
        edit.put("unitPrice", before);
        post("/api/trade/price-plans/" + planNo, edit, admin);
    }

    @Test
    void sharing_has_two_views_over_one_dataset() {
        String admin = login("ADMIN");
        JsonNode sites = get("/api/trade/site-sharing?page=1&size=50", admin).okData();
        JsonNode stats = get("/api/trade/site-sharing/stats", admin).okData();
        JsonNode payees = get("/api/trade/payee-sharing?page=1&size=50", admin).okData();

        assertThat(stats.path("total").asInt())
                .as("统计的 total 必须与列表 total 同源，否则两个数字会互相打架")
                .isEqualTo(sites.path("total").asInt());
        assertThat(stats.path("ok").asInt() + stats.path("missing").asInt() + stats.path("invalid").asInt())
                .isEqualTo(stats.path("total").asInt());

        for (JsonNode r : sites.path("list")) {
            assertThat(r.path("state").asText()).isIn("OK", "MISSING", "INVALID");
            // 有问题就必须说清是什么问题
            if (!"OK".equals(r.path("state").asText())) {
                assertThat(r.path("stateDetail").asText()).isNotBlank();
            }
            // 平台留存 = 1 - 各方合计，可能为负（那正是 INVALID 要暴露的）
            assertThat(r.path("platformRate").asDouble())
                    .isCloseTo(1 - r.path("totalRate").asDouble(), org.assertj.core.data.Offset.offset(0.0001));
        }
        assertThat(payees.path("list").isArray()).isTrue();
    }
}
