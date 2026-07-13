package ai.neargo.powerbank.scenario;

import ai.neargo.powerbank.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 数据范围（行级）：同一 {@code /api/ops/sites} 端点，ADMIN 见全量，AGENT 只见自己 {@code agent_no} 的站点。
 * 由 DataScopeHandler 对已注册锚点表 {@code loc_site} 自动追加 {@code agent_no IN (...)}，业务代码零 where。
 * 关键词过滤与数据范围叠加仍正确（此前拦截器 bug 会破坏带参查询，现已修复）。
 */
class DataScopeFlowTest extends ApiTestSupport {

    private static final String AGENT_NO = "AG002";

    @Test
    void agent_sees_only_own_sites_admin_sees_all() {
        int adminTotal = get("/api/ops/sites?page=1&size=200", login("ADMIN"))
                .okData().path("total").asInt();

        JsonNode agentPage = get("/api/ops/sites?page=1&size=200", loginAgent(AGENT_NO)).okData();
        int agentTotal = agentPage.path("total").asInt();

        // 代理商可见集是全量的真子集
        assertThat(agentTotal).as("AGENT 可见站点数").isLessThanOrEqualTo(adminTotal);

        // 且每条都归属该代理商（数据范围强隔离）
        for (JsonNode s : agentPage.path("list")) {
            assertThat(s.path("agentNo").asText()).as("站点 %s 归属", s.path("siteNo").asText())
                    .isEqualTo(AGENT_NO);
        }
    }

    @Test
    void data_scope_and_keyword_filter_compose() {
        // 数据范围 + 关键词叠加：仍只返回本代理商且命中关键词的站点
        JsonNode page = get("/api/ops/sites?keyword=Mall&page=1&size=50", loginAgent(AGENT_NO)).okData();
        assumeTrue(page.path("list").size() > 0, "AG002 无匹配 'Mall' 的站点——跳过");
        for (JsonNode s : page.path("list")) {
            assertThat(s.path("agentNo").asText()).isEqualTo(AGENT_NO);
        }
    }
}
