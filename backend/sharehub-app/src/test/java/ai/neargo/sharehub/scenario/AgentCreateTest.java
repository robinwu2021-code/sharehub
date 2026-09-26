package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代理商建档：编号服务端取 · {@code agent:agent:create} 真的被强制。
 *
 * <h3>这一条为什么存在</h3>
 * 运营端一直**没有「新增代理商」入口** —— 代理商只能靠入驻审核产生，
 * 而权限清单 §代理商「增/改」本来就写着两个码。补入口时发现两件事：
 * <ul>
 *   <li>{@code agent:agent:create} <b>在目录里列着、没有任何端点认它</b>
 *       —— 建档与改档合用一个映射、一律判 {@code :update}。
 *       管理员勾了「代理商 增」以为开了权限，实际配了等于没配
 *       （该码在 {@code known-perm-ssot-gaps.txt} 里登记为「声明未强制」）；</li>
 *   <li>建档时 <b>请求体里的 {@code agentNo} 会被采纳</b> ——
 *       塞一个就能建出任意编号的代理商，而代理编号是分润、划拨、结算都认的业务键。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentCreateTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;
    private final List<String> made = new ArrayList<>();

    @BeforeAll
    void setUp() {
        admin = login("ADMIN");
    }

    @AfterEach
    void clean() {
        for (String no : made) jdbc.update("DELETE FROM agt_agent WHERE agent_no=?", no);
        made.clear();
        jdbc.update("DELETE FROM agt_agent WHERE name LIKE '【测试】%'");
    }

    @Test
    @DisplayName("★ 编号是 AG + 顺序号，且请求体里塞的编号不作数")
    void agent_no_is_server_assigned() {
        JsonNode a = create(Map.of("name", "【测试】取号一"));
        String first = a.path("agentNo").asText();
        assertThat(first).as("形状应当是 AG + 数字，而不是 ULID：%s", first).matches("^AG\\d+$");

        JsonNode b = create(Map.of("name", "【测试】取号二"));
        assertThat(num(b.path("agentNo").asText()))
                .as("第二个应当比第一个大 1（%s → %s）", first, b.path("agentNo").asText())
                .isEqualTo(num(first) + 1);

        // 塞一个编号进请求体：必须被忽略
        JsonNode c = create(Map.of("name", "【测试】硬塞编号", "agentNo", "AG-I-TYPED-THIS"));
        assertThat(c.path("agentNo").asText()).as("请求体里的编号被采纳了").matches("^AG\\d+$");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agt_agent WHERE agent_no=?",
                Integer.class, "AG-I-TYPED-THIS")).isZero();
    }

    @Test
    @DisplayName("★ 建档判的是 agent:agent:create —— 这个码此前没有任何端点认它")
    void create_is_guarded_by_its_own_perm_code() {
        /*
         * 判据不是「ADMIN 能建」（超管持 `*`，换成任何码它都能过，那样这条恒绿），
         * 而是「**只有 :update、没有 :create** 的角色建不出来」。
         * 用一个临时角色来问这个问题，免得依赖种子角色当下的配码。
         */
        String role = "TMPAGT";
        jdbc.update("DELETE FROM iam_role_perm WHERE role_no=?", role);
        jdbc.update("INSERT INTO iam_role_perm (role_no, perm_code) VALUES (?, 'agent:agent:read'), (?, 'agent:agent:update')", role, role);
        try {
            String tk = login(role);
            // 先证明这个角色确实是"能用的"：读得到列表
            assertThat(get("/api/agent/agents?page=1&size=1", tk).status).as("前提：这个角色至少读得到").isEqualTo(200);
            // 只有 :update 的人**建不出来**
            assertThat(post("/api/agent/agents", Map.of("name", "【测试】只有update"), tk).status)
                    .as("只有 :update 却建出来了 —— 说明建档判的还是 :update").isEqualTo(403);
            // 改档这条路仍然走得通（拆码不该把改档也锁掉）
            JsonNode mine = create(Map.of("name", "【测试】给它改"));
            String no = mine.path("agentNo").asText();
            assertThat(post("/api/agent/agents/" + no, Map.of("name", "【测试】给它改-已改"), tk).status)
                    .as(":update 应当还能改档").isEqualTo(200);
        } finally {
            jdbc.update("DELETE FROM iam_role_perm WHERE role_no=?", role);
        }
    }

    @Test
    @DisplayName("只填名称也能建 —— record 上的原始类型字段会让 Jackson 直接 400")
    void a_minimal_body_is_accepted() {
        /*
         * `Agent` 的 shareRate / cabinetCount 原先是 **原始类型**，
         * 而 record 上的原始类型在 Jackson 里不能缺 —— 缺了直接 400，
         * 且错误信息说的是反序列化失败，看不出少了哪个字段。
         * 「新增代理商」这种只填名称与联系方式的请求因此根本发不出去。
         *
         * cabinetCount 尤其没理由必填：它是只出不入的聚合值，保存时根本不回写。
         */
        JsonNode a = create(Map.of("name", "【测试】只填名称"));
        assertThat(a.path("agentNo").asText()).matches("^AG\\d+$");
        assertThat(a.path("cabinetCount").asInt()).as("聚合值由服务端算").isZero();
    }

    private JsonNode create(Map<String, ?> body) {
        Map<String, Object> m = new HashMap<>(body);
        JsonNode a = post("/api/agent/agents", m, admin).okData();
        made.add(a.path("agentNo").asText());
        return a;
    }

    private static long num(String agentNo) {
        return Long.parseLong(agentNo.substring(2));
    }
}
