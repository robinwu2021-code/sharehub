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
 * 运营端建的代理商**要能登录**。
 *
 * <h3>在此之前这条链是断的</h3>
 * 代理端登录走
 * <pre>手机号 → agt_principal.phone_hash → agt_account.principal_no → agent_no</pre>
 * 而运营端的两个入口<b>都不产生 {@code principal_no}</b>：
 * <ul>
 *   <li>「新增代理商」只写 {@code agt_agent}；</li>
 *   <li>「新增代理账号」的 {@code beforeCreate} 只要求 {@code agentNo}，从不设 principal。</li>
 * </ul>
 * 于是运营能建代理商、能给它开账号、能划拨资产、能配分润，
 * <b>唯独那个代理商自己永远进不来</b>，而且两处都不报错 ——
 * 这正是「功能齐全但整条链不通」最难被发现的形态。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentGrantLoginTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;
    private final List<String> agents = new ArrayList<>();

    @BeforeAll
    void setUp() {
        admin = login("ADMIN");
    }

    @AfterEach
    void clean() {
        for (String no : agents) {
            jdbc.update("DELETE FROM agt_account WHERE agent_no=?", no);
            jdbc.update("DELETE FROM agt_agent WHERE agent_no=?", no);
        }
        agents.clear();
        jdbc.update("DELETE FROM agt_principal WHERE phone_mask LIKE '%0099%' OR email_mask LIKE '%@partner.ae'");
    }

    @Test
    @DisplayName("★ 开通后整条登录链接得上：自然人 + 属主成员关系，都指得回这个代理")
    void granting_login_creates_principal_and_owner_membership() {
        String no = createAgent("【测试】能登录的代理", "+971500000099");

        JsonNode r = post("/api/agent/agents/" + no + "/login-account", Map.of(), admin).okData();
        String accountNo = r.path("accountNo").asText();
        assertThat(accountNo).isNotBlank();

        Map<String, Object> acc = jdbc.queryForMap(
                "SELECT principal_no, is_owner, is_primary, status FROM agt_account WHERE account_no=?", accountNo);
        // 这一条是全部意义所在：没有 principal_no，登录时按自然人查不到它
        assertThat(acc.get("principal_no")).as("成员关系没挂上自然人 —— 登录链断在这里").isNotNull();
        // TINYINT(1) 被 JDBC 读回 Boolean —— 写 isEqualTo(1) 会得到「expected 1 but was true」
        assertThat(String.valueOf(acc.get("is_owner"))).as("从档案开通的是属主").isIn("1", "true");
        assertThat(acc.get("status")).isEqualTo("ACTIVE");

        // 自然人那一行存的是 hash 与掩码，**不存明文**
        Map<String, Object> pr = jdbc.queryForMap(
                "SELECT phone_hash, phone_mask, hash_ver FROM agt_principal WHERE principal_no=?",
                acc.get("principal_no"));
        assertThat(String.valueOf(pr.get("phone_hash"))).as("hash 没算出来").hasSizeGreaterThan(16);
        assertThat(String.valueOf(pr.get("phone_mask"))).as("掩码用于显示，不该是明文")
                .isNotEqualTo("+971500000099");
        assertThat(pr.get("hash_ver")).as("pepper 版本要记下来，否则轮换时无从重算").isNotNull();
    }

    @Test
    @DisplayName("★ 重复开通是幂等的 —— 重复点不该建出第二个成员关系")
    void granting_twice_is_idempotent() {
        String no = createAgent("【测试】重复开通", "+971500000099");
        String first = post("/api/agent/agents/" + no + "/login-account", Map.of(), admin)
                .okData().path("accountNo").asText();
        String second = post("/api/agent/agents/" + no + "/login-account", Map.of(), admin)
                .okData().path("accountNo").asText();

        assertThat(second).as("第二次应当返回同一条").isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agt_account WHERE agent_no=?", Integer.class, no))
                .as("建出了第二行成员关系").isEqualTo(1);
    }

    @Test
    @DisplayName("档案上没有联系手机时拒绝，而不是建一个登不进去的空壳")
    void no_phone_no_login() {
        String no = createAgent("【测试】没手机号", null);
        Resp r = post("/api/agent/agents/" + no + "/login-account", Map.of(), admin);
        assertThat(r.status).as("%s", r.body).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agt_account WHERE agent_no=?", Integer.class, no))
                .as("拒了就不该留下半行").isZero();
    }

    @Test
    @DisplayName("只有手机号、没有邮箱也要拒 —— 自然人有两个登录标识，缺一个建不出来")
    void no_email_no_login() {
        /*
         * `agt_principal.email_hash` 是 NOT NULL + UNIQUE。放宽成可空会让
         * 「建得出但只能用手机号登」的自然人混进来，而邮箱那条登录路径查不到它。
         * 与入驻申请一致：那边手机号与邮箱本来就都是必填。
         */
        String no = createAgent("【测试】只有手机号", "+971500000099", null);
        Resp r = post("/api/agent/agents/" + no + "/login-account", Map.of(), admin);
        assertThat(r.status).as("%s", r.body).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agt_account WHERE agent_no=?", Integer.class, no))
                .as("拒了就不该留下半行").isZero();
    }

    @Test
    @DisplayName("★ 同一个人服务两家主体：复用同一个自然人，不重复建")
    void the_same_person_across_two_agents_reuses_one_principal() {
        /*
         * ADR-030 的核心场景。复用时**绝不动 cred_ref** —— 一个人一套密码，
         * 在这里顺手重置它等于把他在别家主体的登录也改了。
         * 另外 is_primary 只给第一个主体：两个 is_primary=1 时登录后进哪家是不确定的。
         */
        String a1 = createAgent("【测试】同人主体一", "+971500000099");
        String a2 = createAgent("【测试】同人主体二", "+971500000099");
        post("/api/agent/agents/" + a1 + "/login-account", Map.of(), admin).okData();
        post("/api/agent/agents/" + a2 + "/login-account", Map.of(), admin).okData();

        List<String> principals = jdbc.queryForList(
                "SELECT DISTINCT principal_no FROM agt_account WHERE agent_no IN (?,?)", String.class, a1, a2);
        assertThat(principals).as("同一个手机号建出了两个自然人").hasSize(1);

        Integer primaries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agt_account WHERE principal_no=? AND is_primary=1", Integer.class,
                principals.get(0));
        assertThat(primaries).as("同一人至多一个 is_primary，否则登录进哪家不确定").isEqualTo(1);
    }

    private String createAgent(String name, String phone) {
        return createAgent(name, phone, phone == null ? null : "grant" + phone.replaceAll("\\D", "") + "@partner.ae");
    }

    private String createAgent(String name, String phone, String email) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        if (phone != null) m.put("phone", phone);
        if (email != null) m.put("email", email);
        String no = post("/api/agent/agents", m, admin).okData().path("agentNo").asText();
        agents.add(no);
        return no;
    }
}
