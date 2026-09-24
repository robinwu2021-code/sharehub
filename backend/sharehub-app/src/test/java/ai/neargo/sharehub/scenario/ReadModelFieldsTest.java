package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **读接口必须带出前端真正绑定的字段**（《前后端对齐缺口》B 类）。
 *
 * <p>这一类缺口比 404 难查得多：前端拿到 {@code undefined}，
 * <b>页面不报错、只是某一格空白或某个下拉是空的</b>，而没有任何日志。
 *
 * <p>两处都不是「少显示一个字段」那么轻：它们都是**必填下拉/判定条件**的来源，
 * 缺了就让整张表单提交不了，或者让规则按空条件命中。
 */
class ReadModelFieldsTest extends ApiTestSupport {

    @Test
    void contract_list_carries_venue_and_site_numbers() {
        // 合同是场地方分成的唯一依据，按**编号**连；名字只是展示冗余
        //（同一商场不同楼层会有同名站点，按名字连必然连错）。
        // 编辑抽屉的「场地方」「站点」两个下拉都是必填，绑的就是这两个编号 ——
        // 读接口不带出来，打开编辑就是两个空下拉，表单根本提交不了。
        String admin = login("ADMIN");
        Map<String, Object> c = new HashMap<>();
        c.put("venueNo", "VEN301");
        c.put("siteNo", "ST311");
        c.put("venueName", "字段测试场地方");
        c.put("siteName", "字段测试站点");
        c.put("shareRate", 0.2);
        c.put("entryFee", 100);
        c.put("startAt", "2026-01-01");
        c.put("endAt", "2027-01-01");
        c.put("status", "ACTIVE");
        String no = post("/api/ops/contracts", c, admin).okData().path("contractNo").asText();

        // 翻页找，别只看第一页：loc_contract 已经 208 行、页大小 200，
        // 而列表的 keyword **只匹配场地方名/站点名、不匹配合同号**（LocService.pageContracts），
        // 所以也不能靠过滤。测试库是累积的，任何「一页就是全量」的写法都只是还没到线。
        JsonNode found = findInPages("/api/ops/contracts", "contractNo", no, admin);
        assertThat(found).as("前提：刚建的合同在列表里").isNotNull();
        assertThat(found.path("venueNo").asText(null)).as("场地方编号不能缺").isEqualTo("VEN301");
        assertThat(found.path("siteNo").asText(null)).as("站点编号不能缺").isEqualTo("ST311");
    }

    @Test
    void pricing_schedule_list_carries_the_structured_fields() {
        // V49 起判倍率读的是 days / timeFrom / timeTo，period 只是给人看的中文串。
        // 只回 period 的话，编辑抽屉拿不到真正生效的那几个值：界面上是空的，
        // **一保存就把它们清掉**，而倍率从此按空条件命中 —— 全程不报错。
        String admin = login("ADMIN");
        Map<String, Object> s = new HashMap<>();
        s.put("name", "字段测试时段");
        s.put("period", "周末");
        s.put("days", "6,7");
        s.put("timeFrom", "18:00");
        s.put("timeTo", "22:00");
        s.put("multiplier", 1.2);
        s.put("active", true);
        String ruleNo = post("/api/trade/pricing-schedules", s, admin).okData().path("ruleNo").asText();

        JsonNode found = findInPages("/api/trade/pricing-schedules", "ruleNo", ruleNo, admin);
        assertThat(found).as("前提：刚建的时段在列表里").isNotNull();
        assertThat(found.path("days").asText(null)).as("生效星期是判定条件，不能只留展示串").isEqualTo("6,7");
        assertThat(found.path("timeFrom").asText(null)).isEqualTo("18:00");
        assertThat(found.path("timeTo").asText(null)).isEqualTo("22:00");
    }
}
