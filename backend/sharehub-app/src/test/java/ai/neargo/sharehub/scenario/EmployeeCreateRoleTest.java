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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 建员工时**只给主角色**，授权关系也必须落地。
 *
 * <h3>生产实测撞出来的</h3>
 * 2026-09-26 在 powerbank.ichain.top 上为合同审批链建两个 BD 账号
 * （{@code roleNo: "BD"}，没给 {@code roleNos}）。建出来的两个人：
 * <ul>
 *   <li>员工列表里角色是 <b>拓展</b>；</li>
 *   <li>{@code iam_employee_role} <b>一行都没有</b>；</li>
 *   <li>登录后角色是 <b>VIEWER</b>，一个合同权限码都没有。</li>
 * </ul>
 *
 * <p>根因：{@code syncRoles} 把 {@code roleNos == null} 定义成「不动角色」——
 * 这对**改**是对的（改个电话不该把角色清掉），对**建**是错的：
 * 新人没有「原有角色」可保留，什么都不写就等于没有角色。
 * 两种语义共用一个入口，而少掉的那一半没有任何症状 ——
 * 页面上他是拓展，只有他自己登录进来才发现什么都点不了。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmployeeCreateRoleTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;
    private String created;

    @BeforeAll
    void setUp() {
        admin = login("ADMIN");
    }

    @AfterEach
    void clean() {
        if (created != null) {
            jdbc.update("DELETE FROM iam_employee_role WHERE employee_no=?", created);
            jdbc.update("DELETE FROM iam_employee WHERE employee_no=?", created);
            created = null;
        }
    }

    @Test
    @DisplayName("★ 只给 roleNo 建员工 → iam_employee_role 有那一行，登录拿到的是那个角色的权限")
    void creating_with_only_the_main_role_still_writes_the_mapping() {
        JsonNode e = post("/api/platform/employees",
                Map.of("name", "【测试】建档带角色", "phone", "+971500000901", "roleNo", "BD", "status", "ACTIVE"),
                admin).okData();
        created = e.path("employeeNo").asText();
        assertThat(created).isNotBlank();

        assertThat(e.path("roleNos")).as("出参就该带上这个人的角色集合：%s", e).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_employee_role WHERE employee_no=? AND role_no='BD'",
                Integer.class, created))
                .as("授权关系没落地 —— 列表显示 BD，实际一个权限码都没有").isEqualTo(1);
    }

    @Test
    @DisplayName("改员工时不给 roleNos 仍然「不动角色」——改个电话不该把角色清掉")
    void updating_without_roleNos_keeps_the_existing_roles() {
        JsonNode e = post("/api/platform/employees",
                Map.of("name", "【测试】改档不动角色", "phone", "+971500000902", "roleNo", "BD", "status", "ACTIVE"),
                admin).okData();
        created = e.path("employeeNo").asText();
        // 再给他加一个角色，凑出「多角色」这个前提
        jdbc.update("INSERT INTO iam_employee_role (employee_no, role_no) VALUES (?, 'CS')", created);

        post("/api/platform/employees/" + created,
                Map.of("name", "【测试】改档不动角色", "phone", "+971500000903"), admin).okData();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_employee_role WHERE employee_no=?", Integer.class, created))
                .as("无关编辑把多出来的角色静默抹掉了").isEqualTo(2);
    }

    @Test
    @DisplayName("roleName 认 code 也认 role_no——库里存的是 code，只按 role_no 查会让每个员工的角色名都空着")
    void role_name_resolves_by_code_as_well() {
        JsonNode e = post("/api/platform/employees",
                Map.of("name", "【测试】角色名", "phone", "+971500000904", "roleNo", "BD", "status", "ACTIVE"),
                admin).okData();
        created = e.path("employeeNo").asText();
        // iam_role 里 BD 这一行的 role_no 是 R5、code 是 BD；员工表存的是 BD
        assertThat(e.path("roleName").asText("")).as("角色名没解析出来：%s", e).isNotBlank();
    }
}
