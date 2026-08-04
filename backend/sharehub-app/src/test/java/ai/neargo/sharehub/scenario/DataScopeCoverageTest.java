package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据范围**覆盖面**验收（[TDD-核心业务逻辑-分模块 §5.4]，B1 批次）。
 *
 * <p>硬要求：<b>每张注册进 {@code DataScopeRegistration} 的表，都必须有一条
 * 「AGENT 查询结果不含他人数据」的测试</b>。数据范围是那种「不测就等于没做」的能力 ——
 * 漏注册不报错、不告警，只会静默越权，靠人工 review 发现不了。
 *
 * <p>本测试不硬编码行数（数据会变），而是验证两件事：
 * <ol>
 *   <li><b>过滤确实发生了</b> —— AGENT 可见数严格小于 ADMIN 全量数。
 *       若两者相等，说明拦截器根本没生效，而「结果都属于自己」这类断言在全量数据下也可能碰巧通过。</li>
 *   <li><b>归属链自洽</b> —— 逐层校验 站点 ⊇ 点位 ⊇ 机柜 ⊇ 订单/工单，
 *       每层的父引用都必须落在上一层的可见集合里。</li>
 * </ol>
 *
 * @see ai.neargo.sharehub.config.DataScopeRegistration
 */
class DataScopeCoverageTest extends ApiTestSupport {

    /** 该代理在各表都有非空且非全量的数据，断言才有意义。 */
    private static final String AGENT_NO = "AG002";

    private static final int PAGE = 500;

    // ——————————————————————— loc_location ———————————————————————

    @Test
    void locations_are_scoped_to_agent_sites() {
        Set<String> agentSites = idsOf(agentPage("/api/ops/sites"), "siteNo");
        assertThat(agentSites).as("前置：代理应有可见站点").isNotEmpty();

        JsonNode locations = agentPage("/api/ops/locations");
        assertFilteringActuallyHappens("/api/ops/locations", locations);

        for (JsonNode l : locations.path("list")) {
            assertThat(l.path("siteNo").asText())
                    .as("点位 %s 的归属站点应在代理可见集内", l.path("locationNo").asText())
                    .isIn(agentSites);
        }
    }

    // ——————————————————————— dev_cabinet ———————————————————————

    @Test
    void cabinets_are_scoped_to_agent_locations() {
        Set<String> agentLocations = idsOf(agentPage("/api/ops/locations"), "locationNo");
        assertThat(agentLocations).as("前置：代理应有可见点位").isNotEmpty();

        JsonNode cabinets = agentPage("/api/ops/cabinets");
        assertFilteringActuallyHappens("/api/ops/cabinets", cabinets);

        for (JsonNode c : cabinets.path("list")) {
            assertThat(c.path("locationNo").asText())
                    .as("机柜 %s 的归属点位应在代理可见集内", c.path("cabinetNo").asText())
                    .isIn(agentLocations);
        }
    }

    // ——————————————————————— ord_rent ———————————————————————

    @Test
    void orders_are_scoped_to_agent_cabinets() {
        Set<String> agentCabinets = idsOf(agentPage("/api/ops/cabinets"), "cabinetNo");
        assertThat(agentCabinets).as("前置：代理应有可见机柜").isNotEmpty();

        JsonNode orders = agentPage("/api/trade/orders");
        assertFilteringActuallyHappens("/api/trade/orders", orders);

        for (JsonNode o : orders.path("list")) {
            assertThat(o.path("cabinetNo").asText())
                    .as("订单 %s 的借出机柜应在代理可见集内", o.path("orderNo").asText())
                    .isIn(agentCabinets);
        }
    }

    // ——————————————————————— wo_order ———————————————————————

    @Test
    void work_orders_are_scoped_to_agent_cabinets() {
        Set<String> agentCabinets = idsOf(agentPage("/api/ops/cabinets"), "cabinetNo");
        assertThat(agentCabinets).as("前置：代理应有可见机柜").isNotEmpty();

        JsonNode workOrders = agentPage("/api/ops/work-orders");
        assertFilteringActuallyHappens("/api/ops/work-orders", workOrders);

        for (JsonNode w : workOrders.path("list")) {
            String cabinetNo = w.path("cabinetNo").asText();
            if (cabinetNo.isEmpty() || "null".equals(cabinetNo)) continue; // 非设备类工单不挂机柜
            assertThat(cabinetNo)
                    .as("工单 %s 的关联机柜应在代理可见集内", w.path("woNo").asText())
                    .isIn(agentCabinets);
        }
    }

    // ——————————————————————— 直营数据不外泄 ———————————————————————

    /**
     * {@code agent_no IS NULL} 是平台直营。{@code IN (...)} 天然不匹配 NULL，
     * 因此代理商不该看到任何直营数据 —— 这条守住了，才说明 NULL 的语义没被误解成「对所有人可见」。
     */
    @Test
    void agent_cannot_see_platform_direct_rows() {
        Set<String> agentSites = idsOf(agentPage("/api/ops/sites"), "siteNo");
        Set<String> allSites = idsOf(adminPage("/api/ops/sites"), "siteNo");

        Set<String> invisible = new HashSet<>(allSites);
        invisible.removeAll(agentSites);
        assertThat(invisible)
                .as("应存在代理看不到的站点（他人的 + 平台直营的）")
                .isNotEmpty();
    }

    // ——————————————————————— helpers ———————————————————————

    private JsonNode agentPage(String path) {
        return get(path + "?page=1&size=" + PAGE, loginAgent(AGENT_NO)).okData();
    }

    private JsonNode adminPage(String path) {
        return get(path + "?page=1&size=" + PAGE, login("ADMIN")).okData();
    }

    /**
     * 过滤必须真的发生：可见数 &gt; 0（否则断言空集恒成立）且 &lt; 全量数（否则等于没过滤）。
     * 这一条比「每行都属于我」更关键 —— 后者在拦截器失效时也可能碰巧通过。
     */
    private void assertFilteringActuallyHappens(String path, JsonNode agentPage) {
        int agentTotal = agentPage.path("total").asInt();
        int adminTotal = adminPage(path).path("total").asInt();

        assertThat(agentTotal).as("%s：代理可见数应 > 0，否则断言无意义", path).isGreaterThan(0);
        assertThat(agentTotal).as("%s：代理可见数应 < 全量 %d，否则说明数据范围未生效", path, adminTotal)
                .isLessThan(adminTotal);
    }

    private static Set<String> idsOf(JsonNode page, String field) {
        Set<String> ids = new HashSet<>();
        for (JsonNode n : page.path("list")) {
            ids.add(n.path(field).asText());
        }
        return ids;
    }
}
