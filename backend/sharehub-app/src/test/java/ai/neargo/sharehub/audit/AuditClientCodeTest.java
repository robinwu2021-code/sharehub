package ai.neargo.sharehub.audit;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计要记得出**从哪个端**做的（T1-3）。
 *
 * <h2>这一列解决什么</h2>
 * 运营端与代理端共用 {@code /api/**} 和同一套审计。一条「建了工单」「改了分润规则」，
 * 只看 actor 分不清是<b>运营替代理做的</b>还是<b>代理自己做的</b> ——
 * 而这是结算争议里第一个被问到的问题。
 *
 * <h2>为什么其中一条测的是「伪造的请求头无效」</h2>
 * 最省事的实现是让前端传 {@code X-Client}。但<b>审计字段如果能被被审计方自己设置，
 * 比没有这个字段更糟</b>：它会让人以为那一列可信，而实际上改个 header 就能伪造，
 * 伪造出来的记录和真的长得一模一样，事后没有任何办法分辨。
 *
 * <p>今天没有任何代码读这个头，所以那条断言是「天然成立」的 ——
 * 它的价值不在现在，在于<b>将来谁把它「优化」成读请求头时会当场红</b>，
 * 而不是等到某次审计对不上才发现。
 */
class AuditClientCodeTest extends ApiTestSupport {

    @Test
    @DisplayName("运营台的写操作记为 OPS")
    void writes_from_the_ops_console_are_tagged_as_ops() {
        String admin = login("ADMIN");
        createWorkOrder(admin, "[审计端标识] 运营建单");

        assertThat(latestAuditOf("admin.user").path("clientCode").asText())
                .as("运营台的写操作应记为 OPS").isEqualTo("OPS");
    }

    @Test
    @DisplayName("★ 代理自己的写操作记为 AGENT —— 这条区分不出来，这一列就白加了")
    void writes_from_the_agent_portal_are_tagged_as_agent() {
        String agent = loginAgent("AG002");
        createWorkOrder(agent, "[审计端标识] 代理报修");

        assertThat(latestAuditOf("agent.AG002").path("clientCode").asText())
                .as("代理自己做的应记为 AGENT，而不是跟着运营一起记成 OPS")
                .isEqualTo("AGENT");
    }

    @Test
    @DisplayName("★★ 伪造 X-Client 请求头改不了审计里记的端")
    void a_forged_client_header_cannot_change_the_recorded_client() {
        String admin = login("ADMIN");
        Map<String, Object> body = woBody("[审计端标识] 带伪造头");

        postWithHeader("/api/ops/work-orders", body, admin, "X-Client", "MP").okData();

        assertThat(latestAuditOf("admin.user").path("clientCode").asText())
                .as("""
                        clientCode 必须由服务端从会话 realm 派生。
                        这条红了说明有人改成读请求头了 —— 那等于让被审计方自己填「我是从哪个端做的」，
                        而伪造出来的记录和真的长得一模一样。""")
                .isEqualTo("OPS");
    }

    // ——————————————————————— 脚手架 ———————————————————————

    private static Map<String, Object> woBody(String desc) {
        Map<String, Object> b = new HashMap<>();
        b.put("type", "FAULT");
        b.put("source", "MANUAL");
        b.put("priority", "HIGH");
        b.put("description", desc);
        return b;
    }

    private void createWorkOrder(String token, String desc) {
        post("/api/ops/work-orders", woBody(desc), token).okData();
    }

    /**
     * 该操作人最新的一条审计。列表按 id 倒序（{@code AuditLogServiceImpl.page}），
     * 所以刚写完的那条就在第一行 —— 用 keyword 过滤到本人，避免并行用例互相干扰。
     */
    private JsonNode latestAuditOf(String actor) {
        // 固定用 ADMIN 读 —— 审计查询要 org:audit:read，代理没有这个码
        JsonNode list = get("/api/platform/audit-logs?page=1&size=5&keyword=" + actor, login("ADMIN"))
                .okData().path("list");
        assertThat(list).as("审计里应能查到 %s 刚才的写操作", actor).isNotEmpty();
        return list.get(0);
    }
}
