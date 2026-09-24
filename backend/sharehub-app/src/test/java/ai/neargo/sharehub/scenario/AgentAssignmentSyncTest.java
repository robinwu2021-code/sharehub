package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代理划拨的归属回写（B1 后续）：划拨不只落流水，还要把 {@code agent_no} 级联写进
 * {@code loc_site} / {@code loc_location} / {@code dev_cabinet}，否则数据范围过滤看不到划拨结果 ——
 * 「划拨成功了但代理登录后啥也看不到」。
 *
 * <p>验证方式是<b>端到端的可见性</b>而非直接查库：划拨后用该代理的 token 去查，
 * 能看到才算真生效。直接断言库里的列会漏掉「列写对了但没注册数据范围」这类问题。
 *
 * @see ai.neargo.sharehub.agent.ext.AgentOwnershipSync
 */
class AgentAssignmentSyncTest extends ApiTestSupport {

    /** 测试站点：初始为平台直营（{@code agent_no IS NULL}），下挂 3 点位 / 7 机柜。 */
    private static final String SITE = "ST300";

    /** 划入方：与 {@link DataScopeCoverageTest} 用的 AG002 错开，避免互相干扰。 */
    private static final String AGENT = "AG006";

    @AfterEach
    void restoreDirectOperation() {
        // 无论断言是否通过，都把站点收回直营，避免污染其它测试的可见集
        try {
            assign(SITE, AGENT, "REVOKE");
        } catch (Exception ignored) {
            // 收回失败不应掩盖原始断言失败
        }
    }

    @Test
    void site_assignment_cascades_to_locations_and_cabinets() {
        // 划拨前：该代理看不到这个站点
        assertThat(visibleSiteNos()).as("前置：划拨前 %s 不该可见", SITE).doesNotContain(SITE);

        assign(SITE, AGENT, "ASSIGN");

        // 站点本身
        assertThat(visibleSiteNos()).as("划拨后站点应对代理可见").contains(SITE);

        // 级联到点位：该站点下的点位都应可见
        long locsOfSite = pageAll("/api/ops/locations", agentToken()).stream()
                .filter(l -> SITE.equals(l.path("siteNo").asText())).count();
        assertThat(locsOfSite).as("级联后该站点下的点位应对代理可见").isGreaterThan(0);

        // 级联到机柜：机柜数应随之增加（数据范围锚点是 dev_cabinet.agent_no）
        assertThat(visibleCabinetCount()).as("级联后机柜应对代理可见").isGreaterThan(0);
    }

    @Test
    void revoke_returns_assets_to_platform_direct() {
        assign(SITE, AGENT, "ASSIGN");
        assertThat(visibleSiteNos()).contains(SITE);
        int cabsWhenOwned = visibleCabinetCount();

        assign(SITE, AGENT, "REVOKE");

        assertThat(visibleSiteNos()).as("收回后站点不该再对代理可见").doesNotContain(SITE);
        assertThat(visibleCabinetCount())
                .as("收回应把级联的机柜一并撤出代理可见集")
                .isLessThan(cabsWhenOwned);
    }

    @Test
    void assign_to_unknown_agent_is_rejected() {
        // 划给不存在的代理会让资产变孤儿：冗余列写进去了，但没有任何账号的数据范围能匹配到
        assertThat(assignRaw(SITE, "AG_NOT_EXIST", "ASSIGN").status)
                .as("划给不存在的代理应被拒绝")
                .isEqualTo(400);
    }

    @Test
    void assign_unknown_target_is_rejected() {
        assertThat(assignRaw("ST_NOT_EXIST", AGENT, "ASSIGN").status)
                .as("划拨不存在的对象应被拒绝")
                .isEqualTo(400);
    }

    // ——————————————————————— helpers ———————————————————————

    private void assign(String siteNo, String agentNo, String action) {
        assignRaw(siteNo, agentNo, action).okData();
    }

    private Resp assignRaw(String siteNo, String agentNo, String action) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentNo", agentNo);
        body.put("targetType", "SITE");
        body.put("targetNo", siteNo);
        body.put("action", action);
        body.put("operator", "test.admin");
        return post("/api/agent/assignments", body, login("ADMIN"));
    }

    private String agentToken() {
        return loginAgent(AGENT);
    }

    private java.util.Set<String> visibleSiteNos() {
        JsonNode page = get("/api/ops/sites?page=1&size=500", agentToken()).okData();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonNode s : page.path("list")) ids.add(s.path("siteNo").asText());
        return ids;
    }

    private int visibleCabinetCount() {
        return get("/api/ops/cabinets?page=1&size=500", agentToken()).okData().path("total").asInt();
    }
}
