package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代理商**只能经审核产生**，运营端没有直连建档的路。
 *
 * <h3>为什么收掉直连建档</h3>
 * 此前有两条路：入驻申请（自助或代建录入 → 受理 → 审核）和
 * {@code POST /api/agent/agents} 直连建档。后者建出来就是 {@code ENABLED}，
 * 立刻能被划拨资产、配分润，**全程零审核**。
 *
 * <p>而同一个系统里，入驻那条路刻意把「录入」{@code agent:apply:create} 与
 * 「放行」{@code agent:apply:approve} 分成两个码来立四眼。
 * 两条路的治理强度差这么多**不是设计** —— 是直连那条先存在、审核流后补，
 * 没人回头看过它们的关系。2026-09-26 裁决：不论代理商自助注册还是运营端代录，
 * 都要过审核。
 *
 * <p>「运营要能在后台加代理商」这个需求由**代建录入**满足，它走完整审核。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentCreateTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;

    /*
     * 每个用例重新登录，**不用 @BeforeAll 取一次** ——
     * 本类里有用例会新增/删除角色权限行，那会 bump PermVersion 触发会话重建；
     * 再加上令牌存储本身有容量上限，一次取来全类共用实测会拿到 401「登录已失效」，
     * 而那个 401 会伪装成「端点还在/不在」，把断言引到完全错的方向。
     */
    @BeforeEach
    void setUp() {
        admin = login("ADMIN");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM agt_account WHERE agent_no IN (SELECT agent_no FROM agt_agent WHERE name LIKE '【测试】%')");
        jdbc.update("DELETE FROM agt_agent WHERE name LIKE '【测试】%'");
        jdbc.update("DELETE FROM agt_apply WHERE operator_name LIKE '【测试】%'");
        jdbc.update("DELETE FROM agt_principal WHERE email_mask LIKE '%@selfaudit.test'");
    }

    @Test
    @DisplayName("★ 没有直连建档这条路 —— POST /api/agent/agents 不再存在")
    void there_is_no_direct_create_endpoint() {
        Resp r = post("/api/agent/agents", Map.of("name", "【测试】想直连建"), admin);
        /*
         * 只断言「不是 200」与「库里没长出东西」，**不断言具体状态码**。
         * 实测未映射的 POST 会被转到 `/error` 再过一遍安全过滤链，
         * 回来的是 **401** 而不是 404 —— 我第一版按 404/405 断言，红在
         * 「直连建档的端点还在」上，而端点其实已经没了。
         * 钉状态码等于钉 Spring 的路由细节，那不是这条用例要守的东西。
         */
        assertThat(r.status).as("直连建档似乎还通着：%s", r.body).isNotEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agt_agent WHERE name=?",
                Integer.class, "【测试】想直连建")).isZero();
    }

    @Test
    @DisplayName("★ 代建录入的人不能自己放行 —— 四眼原则要有落脚点，不能只写在注释里")
    void the_recorder_cannot_approve_their_own_application() {
        /*
         * 此前只是把录入码与放行码分开，控制器注释写着「四眼原则以后有落脚点」。
         * 而生产里 BD 持的是 `agent:*` 通配，两个码它都有 —— **分码等于没分**。
         */
        String applyNo = recordApply("【测试】自批测试");

        Resp self = post("/api/agent/applies/" + applyNo + "/audit",
                Map.of("approve", true), admin);
        assertThat(self.status).as("录入人自己把它批了：%s", self.body).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM agt_apply WHERE apply_no=?", String.class, applyNo))
                .as("被拒了就不该留下状态变更").isNotEqualTo("APPROVED");
    }

    @Test
    @DisplayName("换一个人放行就能过，且过了之后代理商才出现")
    void another_person_can_approve_and_only_then_the_agent_exists() {
        String applyNo = recordApply("【测试】他人放行");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agt_agent WHERE name=?",
                Integer.class, "【测试】他人放行"))
                .as("审核通过之前不该有代理商 —— 那正是收掉直连建档要保证的").isZero();

        // 换一个持 approve 码、但不是录入人的角色来批
        String other = "TMPAPV";
        jdbc.update("DELETE FROM iam_role_perm WHERE role_no=?", other);
        jdbc.update("INSERT INTO iam_role_perm (role_no, perm_code) VALUES (?, 'agent:apply:read'), (?, 'agent:apply:approve')",
                other, other);
        try {
            Resp r = post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), login(other));
            assertThat(r.status).as("%s", r.body).isEqualTo(200);
            List<String> nos = jdbc.queryForList("SELECT agent_no FROM agt_agent WHERE name=?",
                    String.class, "【测试】他人放行");
            assertThat(nos).as("审核通过之后才建档").hasSize(1);
            assertThat(nos.get(0)).as("编号形状").matches("^AG\\d+$");
        } finally {
            jdbc.update("DELETE FROM iam_role_perm WHERE role_no=?", other);
        }
    }

    @Test
    @DisplayName("★ 归档/软删掉的编号仍占着唯一键 —— 取号必须把它们算进去")
    void soft_deleted_numbers_still_occupy_the_unique_key() {
        /*
         * 2026-09-26 生产实测炸出来的：审核通过时
         * `Duplicate entry 'AG010' for key 'uk_agent_no'`，而列表里**完全看不到 AG010**
         * （它被软删藏起来了）。两件事撞在一起：
         *   · `uk_agent_no` 不含 deleted 列 —— 这是对的，软删的号被合同/结算/划拨引用着，
         *     不能重新发出去；
         *   · 而取号走 MyBatis-Plus 的 QueryWrapper 时 @TableLogic 会补上 deleted = 0，
         *     **取号看不见那些行**。
         * 于是只要有一个代理被归档，它的号就成了一个洞，下一次取号必然走进去。
         */
        Long maxNow = jdbc.queryForObject(
                "SELECT MAX(CAST(SUBSTRING(agent_no, 3) AS UNSIGNED)) FROM agt_agent WHERE agent_no LIKE 'AG%'",
                Long.class);
        String hole = "AG" + String.format("%03d", (maxNow == null ? 0 : maxNow) + 1);
        // 造一个「软删掉的下一个号」——它就是那个洞
        jdbc.update("INSERT INTO agt_agent (agent_no, tenant_id, name, status, deleted) VALUES (?, 'MAIN', ?, 'ENABLED', 1)",
                hole, "【测试】软删占位");
        try {
            String applyNo = recordApply("【测试】跨过那个洞");
            String other = "TMPAPV2";
            jdbc.update("DELETE FROM iam_role_perm WHERE role_no=?", other);
            jdbc.update("INSERT INTO iam_role_perm (role_no, perm_code) VALUES (?, 'agent:apply:read'), (?, 'agent:apply:approve')",
                    other, other);
            try {
                Resp r = post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), login(other));
                assertThat(r.status).as("撞上软删的号了：%s", r.body).isEqualTo(200);
                String got = jdbc.queryForObject("SELECT agent_no FROM agt_agent WHERE name=?",
                        String.class, "【测试】跨过那个洞");
                assertThat(got).as("取号应当跳过被软删占着的 %s", hole).isNotEqualTo(hole);
            } finally {
                jdbc.update("DELETE FROM iam_role_perm WHERE role_no=?", other);
            }
        } finally {
            jdbc.update("DELETE FROM agt_agent WHERE agent_no=?", hole);
        }
    }

    /** 以 admin 的身份代建录入一张申请单，返回单号。 */
    private String recordApply(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("operatorName", name);
        m.put("operatorType", "AGENT");
        // 手机号与邮箱都必填 —— 自然人的两个登录标识，缺一个审核通过时建不出来
        m.put("phone", "+9715000" + String.format("%05d", Math.abs(name.hashCode()) % 100000));
        m.put("email", "a" + Math.abs(name.hashCode()) + "@selfaudit.test");
        JsonNode r = post("/api/agent/applies", m, admin).okData();
        return r.path("applyNo").asText();
    }
}
