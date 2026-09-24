package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **一个人可以有多个角色**，而且**改别的字段不会把角色清掉**。
 *
 * <h2>为什么第二条比第一条重要</h2>
 * P3 把会话角色接到了 {@code iam_employee_role}，但保存员工时走的是
 * 「删光再插主角色那一条」—— 一旦有了多角色，**任何一次无关编辑**
 * （改个电话号码、改个部门）都会把多出来的角色静默抹掉。
 * 页面上看不出来，当事人只会发现自己忽然少了一半菜单。
 *
 * <p>所以 {@code roleNos == null} 的语义定成「不动角色」，而不是「清空」。
 */
class EmployeeMultiRoleTest extends ApiTestSupport {

    private Map<String, Object> req(String name, List<String> roleNos) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("roleNo", "OPS");
        if (roleNos != null) m.put("roleNos", roleNos);
        return m;
    }

    private List<String> rolesOf(String admin, String employeeNo) {
        JsonNode row = findInPages("/api/platform/employees", "employeeNo", employeeNo, admin);
        assertThat(row).as("前提：%s 在列表里", employeeNo).isNotNull();
        List<String> out = new ArrayList<>();
        row.path("roleNos").forEach(r -> out.add(r.asText()));
        return out;
    }

    private String create(String admin, String name, List<String> roleNos) {
        return post("/api/platform/employees", req(name, roleNos), admin)
                .okData().path("employeeNo").asText();
    }

    @Test
    void an_employee_can_hold_several_roles() {
        String admin = login("ADMIN");
        String no = create(admin, "多角色测试", List.of("OPS", "CS"));
        assertThat(rolesOf(admin, no)).containsExactlyInAnyOrder("OPS", "CS");
    }

    @Test
    void editing_something_else_does_not_wipe_the_roles() {
        /*
         * 这条是本次真正要守的：改名字**不带 roleNos**，角色必须原封不动。
         * 此前的实现会把 CS 删掉，只留主角色 OPS —— 而没有任何地方报错。
         */
        String admin = login("ADMIN");
        String no = create(admin, "改名前", List.of("OPS", "CS"));
        assertThat(rolesOf(admin, no)).hasSize(2);

        Map<String, Object> rename = new HashMap<>();
        rename.put("name", "改名后");   // 刻意不传 roleNo / roleNos
        post("/api/platform/employees/" + no, rename, admin).okData();

        assertThat(rolesOf(admin, no)).as("改名不该动角色")
                .containsExactlyInAnyOrder("OPS", "CS");
    }

    @Test
    void the_primary_role_is_always_part_of_the_set() {
        // 「列表显示 OPS、而授权表里没有 OPS」是最难查的那种不一致
        String admin = login("ADMIN");
        String no = create(admin, "主角色并入", List.of("CS"));
        assertThat(rolesOf(admin, no)).as("主角色 OPS 要并进去").contains("OPS", "CS");
    }

    @Test
    void an_empty_list_still_keeps_the_primary_role() {
        /*
         * 传空表 = 清掉附加角色，但**主角色摘不掉**。
         *
         * 这不是实现漏了，是刻意的不变量：`iam_employee.role_no` 是列表显示的那个，
         * 「页面显示 OPS、而授权表里没有 OPS」正是最难查的那种不一致 ——
         * 会话按授权表给权限，而看列表的人以为他是 OPS。
         * 要真正拿掉一个人的权限，改他的状态（LEFT）或换主角色，不是清空这张表。
         *
         * （写这条用例时我先按「空表就该全空」断言，红了才发现实现是对的。）
         */
        String admin = login("ADMIN");
        String no = create(admin, "清空附加角色", List.of("CS"));
        assertThat(rolesOf(admin, no)).containsExactlyInAnyOrder("OPS", "CS");

        Map<String, Object> clear = new HashMap<>();
        clear.put("name", "清空附加角色");
        clear.put("roleNos", List.of());
        post("/api/platform/employees/" + no, clear, admin).okData();

        assertThat(rolesOf(admin, no)).as("附加的 CS 没了，主角色还在")
                .containsExactly("OPS");
    }

    @Test
    void the_session_takes_the_union_of_all_roles() {
        /*
         * 多角色必须在**会话**上体现为权限并集，否则这个功能只是数据库里的几行。
         * 用 dev-mode 按 employee_no 登录（真实员工登录是 P3b）。
         */
        String admin = login("ADMIN");
        String no = create(admin, "并集测试", List.of("OPS", "FINANCE"));

        List<String> mine = new ArrayList<>();
        get("/api/auth/me", login("VIEWER", no, null)).okData().path("perms")
                .forEach(p -> mine.add(p.asText()));

        List<String> ops = new ArrayList<>();
        get("/api/auth/me", login("OPS", "ops.user", null)).okData().path("perms")
                .forEach(p -> ops.add(p.asText()));
        List<String> fin = new ArrayList<>();
        get("/api/auth/me", login("FINANCE", "fin.user", null)).okData().path("perms")
                .forEach(p -> fin.add(p.asText()));

        assertThat(mine).as("要同时拿到 OPS 的那份").containsAll(ops);
        assertThat(mine).as("也要拿到 FINANCE 的那份").containsAll(fin);
    }
}
