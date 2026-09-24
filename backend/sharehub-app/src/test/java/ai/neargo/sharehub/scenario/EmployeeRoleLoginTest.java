package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **用户 → 角色**：会话的角色来自员工档案（{@code iam_employee_role}），不再来自请求。
 *
 * <h2>此前这一段是空的</h2>
 * 表和同步代码都在，但生产 5 个员工是种子直灌的、没走过 service，
 * 于是 {@code iam_employee_role} <b>0 行</b>（V78 已回填）。
 * 会话角色来自登录请求或配置，与员工档案毫无关系 ——
 * 「按用户动态展示菜单」里的「用户」实际上不存在。
 *
 * <h2>只覆盖授权，不覆盖认证</h2>
 * 「怎么确定是谁」仍是老样子（生产：用户名必须 admin + 共享口令；
 * 员工凭据库至今只存在于注释里）。这里验的是**确定是谁之后**，
 * 他的角色、权限、菜单是不是真的按档案来 —— 见 {@code EmployeeRoles} 类注释。
 */
class EmployeeRoleLoginTest extends ApiTestSupport {

    private List<String> permsOf(String token) {
        List<String> out = new ArrayList<>();
        get("/api/auth/me", token).okData().path("perms").forEach(p -> out.add(p.asText()));
        return out;
    }

    private List<String> menuKeysOf(String token) {
        List<String> out = new ArrayList<>();
        get("/api/auth/menus", token).okData()
                .forEach(s -> out.add(s.path("menuNo").asText()));
        return out;
    }

    @Test
    void roles_come_from_the_employee_directory_not_from_the_request() {
        /*
         * E1003（Sara Ahmed）在档案里是 CS。**即便登录请求里声称自己是 ADMIN**，
         * 拿到的也必须是 CS 的权限 —— 角色由账号决定，客户端说了不算。
         * 这条此前不成立：dev-mode 下请求里写什么角色就是什么角色。
         */
        List<String> asDirectory = permsOf(login("ADMIN", "E1003", null));
        List<String> asCs = permsOf(login("CS", "cs.user", null));
        // 不写死具体权限码 —— 那是在猜。直接比「和 CS 角色拿到的一模一样」，
        // 期望值由角色表自己提供，改了角色配置这条也不会假红。
        assertThat(asDirectory).as("档案是 CS，拿到的就该是 CS 那一份")
                .containsExactlyInAnyOrderElementsOf(asCs);
        assertThat(asDirectory).as("不该拿到超管通配").doesNotContain("*");
    }

    @Test
    void the_menu_follows_the_directory_too() {
        // 授权链要一路通到菜单：档案是 FINANCE，就该看得到财务、看不到系统设置
        List<String> asDirectory = menuKeysOf(login("VIEWER", "E1004", null));
        List<String> asFinance = menuKeysOf(login("FINANCE", "fin.user", null));
        assertThat(asDirectory).as("档案是 FINANCE，菜单就该与 FINANCE 一模一样")
                .containsExactlyInAnyOrderElementsOf(asFinance);
        // 再要一条「确实不是谁都一样」：请求里写的 VIEWER 看得到的更少
        assertThat(asDirectory).as("而不是请求里那个 VIEWER 的菜单")
                .isNotEqualTo(menuKeysOf(login("VIEWER", "viewer.user", null)));
    }

    @Test
    void a_resigned_employee_loses_everything() {
        /*
         * 停用即失去全部角色，不是降级成某个默认角色 ——
         * 「停用了但还剩点权限」比「停用了还全须全尾」更难发现。
         */
        String admin = login("ADMIN");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("employeeNo", "E1002");
        body.put("name", "Omar Khan");
        body.put("roleNo", "OPS");
        body.put("status", "LEFT");
        post("/api/platform/employees/E1002", body, admin).okData();
        try {
            List<String> perms = permsOf(login("ADMIN", "E1002", null));
            assertThat(perms).as("离职后不该还拿着 OPS 的设备权限")
                    .doesNotContain("device:cabinet:*", "device:cabinet:read");
        } finally {
            body.put("status", "ACTIVE");
            post("/api/platform/employees/E1002", body, admin).okData();
        }
    }

    @Test
    void an_unknown_login_name_still_works_the_old_way() {
        /*
         * 生产的登录名今天是 admin，**不等于任何 employee_no**。
         * 认不出就拒绝登录的话，唯一能用的账号会被锁在外面 ——
         * 所以认不出时沿用原来那套。这条守的是「别把生产锁死」。
         */
        JsonNode me = get("/api/auth/me", login("OPS", "ops.user", null)).okData();
        assertThat(me.path("authenticated").asBoolean()).isTrue();
        assertThat(me.path("perms").isArray()).isTrue();
        assertThat(me.path("perms").size()).as("仍按请求里的角色给权限").isGreaterThan(0);
    }
}
