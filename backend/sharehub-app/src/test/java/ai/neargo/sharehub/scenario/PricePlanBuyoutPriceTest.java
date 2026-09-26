package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计费方案的「买断价」此前**永远存不进去** —— 而它同时是总封顶。
 *
 * <p>读出参 {@code PricePlanEntry} 用 {@code buyoutPrice}（实体注释原话：
 * 「列名保留 DDL 的 {@code cap_total}，VO 层才换成前端的 {@code buyoutPrice}」），
 * 而写入 DTO {@code PricePlanReq} 用的是 DB 名 {@code capTotal}。
 * 表单按读出参的名字发 {@code buyoutPrice} 过来，后端读 {@code capTotal} → 落库为 null。
 *
 * <h3>代价不止「少一列显示」</h3>
 * {@code cap_total} 为空的语义是<b>不封顶</b>（{@code PriceResolver}：「0 / 空表示不封顶」）：
 * <ul>
 *   <li>{@code ChargeChain} 拿到 {@code capTotal = null} → 长租<b>无上限计费</b>；</li>
 *   <li>{@code buyoutIfCapped} 开头就 {@code if (cap == null) return false}
 *       → 「逾期达封顶自动买断」<b>永不触发</b>，充电宝也永不转 SOLD。</li>
 * </ul>
 * 读侧显示一直正常（VO 改过名），所以这一格看起来从来没问题。
 *
 * <p>是 `check-form-fields` 给 fee-plans 那两张表单挂上端点后当场报出来的
 * （`PLAN_FIELDS 发出 buyoutPrice / 后端认 capTotal`）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PricePlanBuyoutPriceTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    final List<String> plans = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String p : plans) jdbc.update("DELETE FROM price_plan WHERE plan_no=?", p);
    }

    @Test
    @DisplayName("★★ 买断价存得进 cap_total，读回来还是它 —— 空 cap_total 等于「不封顶」")
    void buyoutPriceLandsInCapTotal() {
        String admin = login("ADMIN");
        String no = "PP_BT" + (System.nanoTime() % 1000000);
        plans.add(no);

        JsonNode created = post("/api/trade/price-plans", plan(no, "100.00"), admin).okData();

        assertThat(created.path("buyoutPrice").decimalValue())
                .as("出参就该带回买断价").isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT cap_total FROM price_plan WHERE plan_no=?", BigDecimal.class, no))
                .as("落库的列是 cap_total —— 存不进去的话它是 null，而 null 的语义是「不封顶」，"
                        + "长租无上限计费、逾期自动买断永不触发")
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("改买断价真的改到了；路径上的方案号为准")
    void buyoutPriceCanBeUpdated() {
        String admin = login("ADMIN");
        String no = "PP_BU" + (System.nanoTime() % 1000000);
        plans.add(no);
        post("/api/trade/price-plans", plan(no, "80.00"), admin).okData();

        Map<String, Object> m = plan(no, "150.00");
        m.put("planNo", "PP-SOMEONE-ELSE");   // body 里的号不该被当真
        post("/api/trade/price-plans/" + no, m, admin).okData();

        assertThat(jdbc.queryForObject("SELECT cap_total FROM price_plan WHERE plan_no=?", BigDecimal.class, no))
                .isEqualByComparingTo("150.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_plan WHERE plan_no=?", Integer.class,
                "PP-SOMEONE-ELSE")).isZero();
    }

    private static Map<String, Object> plan(String no, String buyoutPrice) {
        Map<String, Object> m = new HashMap<>();
        m.put("planNo", no);
        m.put("name", "买断价测试方案");
        m.put("freeMinutes", 10);
        m.put("unitMinutes", 30);
        m.put("unitPrice", new BigDecimal("3.00"));
        m.put("capDaily", new BigDecimal("20.00"));
        m.put("buyoutPrice", new BigDecimal(buyoutPrice));
        m.put("currency", "AED");
        m.put("scope", "ALL");
        m.put("deviceType", "POWERBANK");
        return m;
    }
}
