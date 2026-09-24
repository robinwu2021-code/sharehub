package ai.neargo.sharehub.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计要答得出「改成什么了」（T3-2）。
 *
 * <h2>只记 action 答不出的那个问题</h2>
 * 现在审计记的是 {@code POST /api/trade/share-rules} —— 只知道有人动过这条规则。
 * 而结算争议里要问的是「<b>费率是从 8% 改成 5% 的，还是一直就是 5%</b>」。
 * 去库里查当前值没用：查到的正是被改过之后的那个。
 *
 * <p>读侧（{@code AuditLogServiceImpl.changesOf}）早就在解析 {@code changes} 数组，
 * 只是从来没有人写，于是详情页那张「改前改后」表<b>永远是空的</b> ——
 * 和 {@code requestId} 一样是个半成品：界面上有这一栏，看的人会以为数据丢了。
 */
class AuditFieldChangesTest extends AuditTestSupport {

    @Test
    @DisplayName("★★ 改分润费率，审计里查得出改前改后")
    void changing_a_share_rate_records_what_it_was_and_what_it_became() {
        String admin = login("ADMIN");
        String ruleNo = createRule(admin, "0.08");

        String tp = newTraceparent();
        postWithHeaders("/api/trade/share-rules/" + ruleNo, ruleBody(ruleNo, "0.05"), admin, "traceparent", tp)
                .okData();

        JsonNode changes = changesOfDetail(auditOfThisRequest("admin.user", traceIdOf(tp)));
        assertThat(changes).as("改了费率却没有任何 diff —— 详情页那张表又是空的").isNotEmpty();

        JsonNode rate = findChange(changes, "分润比率");
        assertThat(rate.path("before").asText()).as("改前").isEqualTo("0.08");
        assertThat(rate.path("after").asText()).as("改后").isEqualTo("0.05");
    }

    @Test
    @DisplayName("★ 没变的字段不能出现在 diff 里——否则唯一有用的那条被埋在噪音里")
    void fields_that_did_not_change_are_not_listed() {
        String admin = login("ADMIN");
        String ruleNo = createRule(admin, "0.08");

        String tp = newTraceparent();
        postWithHeaders("/api/trade/share-rules/" + ruleNo, ruleBody(ruleNo, "0.05"), admin, "traceparent", tp)
                .okData();

        JsonNode changes = changesOfDetail(auditOfThisRequest("admin.user", traceIdOf(tp)));
        // 同一次保存里 payeeNo/payeeName/mode/basis/priority 都没动
        for (JsonNode c : changes) {
            assertThat(c.path("field").asText())
                    .as("只有费率变了，别的字段不该出现（「X → X」二十条会把唯一有用的那条藏起来）")
                    .isEqualTo("分润比率");
        }
    }

    @Test
    @DisplayName("★ 原样重存一次，不该产生任何 diff——标度不同不是改动")
    void resaving_the_same_rate_is_not_a_change() {
        // 库里是 DECIMAL(x,4)，取出来是 0.0800；请求体里写 0.08。
        // BigDecimal.equals 认标度，两者不等 —— 按对象比较的话，
        // 一次什么都没改的保存会留下一条「0.0800 → 0.08」，
        // 而这种噪音积累起来会让 diff 这个功能变得没人看。
        String admin = login("ADMIN");
        String ruleNo = createRule(admin, "0.08");

        String tp = newTraceparent();
        postWithHeaders("/api/trade/share-rules/" + ruleNo, ruleBody(ruleNo, "0.08"), admin,
                "traceparent", tp).okData();

        assertThat(changesOfDetail(auditOfThisRequest("admin.user", traceIdOf(tp))))
                .as("什么都没改，diff 就该是空的").isEmpty();
    }

    @Test
    @DisplayName("上一个请求改了什么，不能出现在下一个请求的审计里")
    void changes_do_not_leak_into_the_next_request() {
        String admin = login("ADMIN");
        String ruleNo = createRule(admin, "0.08");
        postWithHeaders("/api/trade/share-rules/" + ruleNo, ruleBody(ruleNo, "0.05"), admin,
                "traceparent", newTraceparent()).okData();

        // 紧接着做一件完全无关的写操作
        String tp = newTraceparent();
        Map<String, Object> venue = new HashMap<>();
        venue.put("name", "[改动泄漏测试] 场地方 " + System.nanoTime());
        venue.put("industry", "购物中心");
        postWithHeaders("/api/ops/venues", venue, admin, "traceparent", tp).okData();

        assertThat(changesOfDetail(auditOfThisRequest("admin.user", traceIdOf(tp))))
                .as("建场地方的审计里不该带着上一次改费率的 diff —— 那不是少了信息，是记了假的")
                .isEmpty();
    }

    // ——————————————————————— 脚手架 ———————————————————————

    /**
     * 新建一条规则并返回编号。
     *
     * <p>新建走 {@code POST /api/trade/share-rules}（它会把 body 里的 ruleNo 抹掉，
     * 一律服务端取号），<b>改要走 {@code /{ruleNo}}</b> —— 两个端点，不是一个。
     * 第一版用错了，测试红在「没有 diff」上，而真实原因是它一直在新建 ——
     * 新建没有「改前」，本来就不该有 diff。
     */
    @Test
    @DisplayName("★★★ 往分润规则的编辑端点塞 deleted / createdBy —— 一个都不许生效")
    void editing_a_share_rule_cannot_soft_delete_it_or_forge_its_audit_trail() {
        String admin = login("ADMIN");
        String ruleNo = createRule(admin, "0.08");

        /*
         * 2026-09-24 之前这两个字段是**真能写进去**的：
         * 端点收的是实体 ShareRule（继承 BaseEntity，带 deleted / createdAt / createdBy），
         * 而 saveRule 是手写保存、不走 AbstractCrudService —— 那次批量赋值集中加固
         * 对它不生效，只回填了 id/version/tenantId。于是：
         *   · {"deleted":1}   → 绕过归档语义把规则软删掉；
         *   · {"createdBy":…} → 伪造审计痕迹，而分润规则正是结算争议时要翻的那张表。
         * 改成 ShareRuleReq 白名单后，这些键根本不在入参里。
         */
        Map<String, Object> poisoned = ruleBody(ruleNo, "0.05");
        poisoned.put("deleted", 1);
        poisoned.put("createdBy", "forged.user");
        poisoned.put("tenantId", "OTHER_TENANT");
        post("/api/trade/share-rules/" + ruleNo, poisoned, admin).okData();

        // 还查得到 = 没被软删；列表按 tenant 隔离，查得到也说明没被搬走
        JsonNode after = get("/api/trade/share-rules?keyword=" + ruleNo, admin).okData();
        JsonNode row = after.path("list").path(0);
        assertThat(row.path("ruleNo").asText())
                .as("规则被 deleted:1 软删了 —— 编辑端点不该能删东西")
                .isEqualTo(ruleNo);
        assertThat(row.path("rate").asText())
                .as("正常字段仍应改得动（别把白名单收得过窄）")
                .isEqualTo("0.05");
    }

    private String createRule(String admin, String rate) {
        Map<String, Object> b = ruleBody(null, rate);
        b.put("payeeName", "[改前改后测试] 分成方");
        return post("/api/trade/share-rules", b, admin).okData().path("ruleNo").asText();
    }

    private static Map<String, Object> ruleBody(String ruleNo, String rate) {
        Map<String, Object> b = new HashMap<>();
        if (ruleNo != null) b.put("ruleNo", ruleNo);
        b.put("dimension", "AGENT");
        b.put("payeeNo", "AG002");
        b.put("payeeName", "[改前改后测试] 分成方");
        b.put("basis", "OPERATE");
        b.put("mode", "LEDGER");
        b.put("priority", 5);
        b.put("rate", rate);
        return b;
    }

    private JsonNode changesOfDetail(JsonNode auditRow) {
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
        throw new AssertionError("diff 里没有字段 " + field + "：" + changes);
    }
}
