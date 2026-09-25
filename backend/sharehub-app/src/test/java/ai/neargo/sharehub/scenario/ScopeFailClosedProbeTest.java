package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **探针**：一张表只登记了 AGENT 维度时，SITE 范围的员工还看得到它吗？
 *
 * <h2>为什么这一条决定整批工作能不能做</h2>
 * {@code known-unscoped-owned-tables.txt} 里 51 张表带归属列却没登记数据范围，
 * 而 {@code DataScopeRegistration} 的类注释说 handler 是 <b>fail-closed</b>：
 * 「当前 spec 的维度在本表锚点里找不到列」时生成 {@code 1=0}（全部拒绝）而不是放行。
 *
 * <p>如果这句成立，那么 {@code agt_account}/{@code agt_commission} 这类
 * <b>只有 agent_no、没有 site_no/region_id</b> 的表就<b>不能只登记 AGENT</b> ——
 * 一登记，按站点收敛的员工在那几个页面上会<b>一行都看不到，而且不报错</b>。
 * 那 51 张表迟迟没登记，很可能不是没人做，是做不了。
 *
 * <p>所以在动任何一张表之前，先用实验把这句话验到底：
 * 本测试临时依赖 {@code DataScopeRegistration} 里对 {@code agt_commission}
 * 只登记 AGENT 的那一行（实验用，验完回滚）。
 */
class ScopeFailClosedProbeTest extends ApiTestSupport {

    private static final String WHO = "E-FAILCLOSED-PROBE";

    private void setScope(String admin, String no, String type, String refs) {
        Map<String, Object> body = new HashMap<>();
        body.put("scopeType", type);
        body.put("scopeRefs", refs);
        put("/api/platform/data-scopes/EMPLOYEE/" + no, body, admin).okData();
    }

    private int commissionCount(String token) {
        return get("/api/agent/commissions?page=1&size=200", token).okData().path("list").size();
    }

    @Test
    @DisplayName("只登记 AGENT 的表，SITE 范围的员工看得到吗")
    void what_happens_to_a_site_scoped_staff() {
        String admin = login("ADMIN");

        // agt_commission 在测试库里是空表 —— 自己造一条，不依赖种子
        Map<String, Object> rule = new HashMap<>();
        rule.put("agentNo", "AG001");
        rule.put("dimension", "GMV");
        rule.put("rate", new java.math.BigDecimal("0.15"));
        rule.put("mode", "LEDGER");
        rule.put("currency", "AED");
        post("/api/agent/commissions", rule, admin).okData();

        int asAdmin = commissionCount(admin);
        assertThat(asAdmin).as("前提：刚建了一条，ADMIN（ALL）应当看得到").isGreaterThan(0);

        String token = login("ADMIN", WHO, null);
        int beforeNarrowing = commissionCount(token);
        assertThat(beforeNarrowing).as("前提：收敛之前这个人也看得到").isGreaterThan(0);

        // 拿一个真站点号收敛
        String site = get("/api/ops/sites?page=1&size=1", admin).okData()
                .path("list").get(0).path("siteNo").asText();
        setScope(admin, WHO, "SITE", site);
        try {
            int afterNarrowing = commissionCount(token);
            System.out.println("[探针] ALL=" + asAdmin
                    + "  收敛前=" + beforeNarrowing
                    + "  SITE 收敛后=" + afterNarrowing
                    + "  → " + (afterNarrowing == 0
                        ? "fail-closed 成立：只登记 AGENT 的表会让 SITE 员工全瞎"
                        : "fail-closed 不成立（或该维度被放行），可以只登记 AGENT"));
            // 不断言，只取证 —— 这一条是用来做判断的，不是用来守行为的
            assertThat(afterNarrowing).isGreaterThanOrEqualTo(0);
        } finally {
            setScope(admin, WHO, "ALL", "");
        }
    }
}
