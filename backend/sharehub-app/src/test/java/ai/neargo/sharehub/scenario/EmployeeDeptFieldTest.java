package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 员工的「部门」字段。
 *
 * <h2>和 roleName → roleNo 是同一个 bug，只差一行</h2>
 * {@code empFields} 上方的注释记着：那里原先是个自由文本框（key 为 {@code roleName}），
 * 而后端认 {@code roleNo}，填进去的名字**被静默丢弃**，那个人的角色一直是空的 ——
 * 于是他登录后什么菜单都没有，而表单上明明写着「运维」。
 *
 * <p>紧接下一行的 {@code deptName} 是同一个形状，当时没一起改：
 * 出参 {@code Employee.deptName} 是**显示名**（由 {@code deptNo} 查 {@code iam_dept} 得来），
 * 写入面 {@code EmployeeReq} 要的是 {@code deptNo}。运营在「部门」里打「运维」，
 * 接口 200，库里 {@code dept_no} 一直是空的。
 *
 * <p>部门不像角色那样决定权限，所以没有「登录后看不到菜单」这种响亮症状 ——
 * 它只是让按部门筛人、按部门派单、按部门统计这些事永远筛不出东西。
 *
 * <h2>出参也要带 deptNo</h2>
 * 光让表单发 {@code deptNo} 不够：编辑既有员工时，下拉得能预选当前部门，
 * 而出参只有显示名。角色那边出参是 {@code roleNo} + {@code roleName} 两个都给，
 * 部门照同一口径补上 {@code deptNo}。
 */
class EmployeeDeptFieldTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String someDept() {
        return jdbc.queryForObject("SELECT dept_no FROM iam_dept ORDER BY dept_no LIMIT 1", String.class);
    }

    private JsonNode row(String admin, String no) {
        return findInPages("/api/platform/employees", "employeeNo", no, admin);
    }

    @Test
    @DisplayName("★★ 建员工时传的 deptNo 要落库——运营端此前发的是 deptName，静默丢弃")
    void deptNoSurvivesCreate() {
        String admin = login("ADMIN");
        String dept = someDept();
        assertThat(dept).as("前提：库里得有部门").isNotBlank();

        Map<String, Object> m = new HashMap<>();
        m.put("name", "部门字段探针");
        m.put("phone", "+971500000001");
        m.put("deptNo", dept);
        m.put("roleNo", "OPS");
        String no = post("/api/platform/employees", m, admin).okData().path("employeeNo").asText();
        assertThat(no).as("前提：员工建出来了").isNotBlank();

        try {
            assertThat(jdbc.queryForObject("SELECT dept_no FROM iam_employee WHERE employee_no=?", String.class, no))
                    .as("库里要真落下来")
                    .isEqualTo(dept);
            assertThat(row(admin, no).path("deptNo").asText(""))
                    .as("出参也要带编号 —— 否则编辑时下拉预选不出当前部门，一打开就变成「没有部门」")
                    .isEqualTo(dept);
            assertThat(row(admin, no).path("deptName").asText(""))
                    .as("显示名照旧给，列表那一列靠它")
                    .isNotBlank();
        } finally {
            jdbc.update("DELETE FROM iam_employee_role WHERE employee_no=?", no);
            jdbc.update("DELETE FROM iam_employee WHERE employee_no=?", no);
        }
    }
}
