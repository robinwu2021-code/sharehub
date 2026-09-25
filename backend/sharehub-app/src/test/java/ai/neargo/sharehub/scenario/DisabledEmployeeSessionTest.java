package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **停用一个员工，他手里的令牌必须当场失效。**
 *
 * <h2>补之前是什么样</h2>
 * 三件事凑在一起，让「停用」只改了一行数据、没改任何实际权限：
 * <ul>
 *   <li>{@code TokenStore} 只有 {@code revoke(token)}；{@code sys_token.subject_no}
 *       没有索引，Redis 那边连二级索引都没有 —— <b>「撤销某人的全部会话」根本做不到</b>；</li>
 *   <li>看起来有现成机制：{@code PermVersion.bump()} 之后 {@code StaffTokenAuthFilter}
 *       会重建会话。但 {@code PermissionService.rebuild} 是按<b>会话里带来的</b>
 *       {@code roleCodes} 重算权限的，<b>全程不读 {@code iam_employee}</b>；</li>
 *   <li>而且员工保存路径压根没有 {@code bump()} —— 戳没变，连重建那一步都走不到。</li>
 * </ul>
 * ⇒ 离职的人拿着旧令牌可以一直用到令牌自然过期（默认 2 小时，且每次请求都会续期）。
 *
 * <h2>修法：两件事合起来才成立</h2>
 * 保存员工时 {@code bump()}（戳变了 → 下一次请求走重建）
 * + {@code rebuild} 回查在职（重建时发现人已离职 → 返回 null → 会话作废）。
 * 少任何一件都不成立，所以下面两条断言缺一不可。
 *
 * <h2>为什么还要一条「别人不受影响」</h2>
 * 第一版的回查写成了「查不到这个员工也判失效」，看起来更保守，
 * 实际是**一次 bump 就把所有人挡在门外** —— 会话里的 {@code userNo} 是登录名，
 * 而登录名不一定等于 {@code employee_no}（{@code AuthController} 自己的注释写明
 * 「生产的登录名今天是 admin，不等于任何 employee_no」）。
 * 所以 {@link #other_sessions_survive_the_bump} 与前两条同等重要：
 * 它守的是「这道闸没有误伤」。
 */
class DisabledEmployeeSessionTest extends ApiTestSupport {

    /** 共享有状态测试库：编号必须每次不同，否则第二次跑撞唯一键。 */
    private static String uniqueNo() {
        return "EMP_T" + System.nanoTime();
    }

    private String createActiveEmployee(String admin, String employeeNo) {
        Map<String, Object> m = new HashMap<>();
        m.put("employeeNo", employeeNo);
        m.put("name", "停用回归-" + employeeNo);
        m.put("roleNo", "OPS");
        m.put("roleNos", List.of("OPS"));
        m.put("status", "ACTIVE");
        return post("/api/platform/employees", m, admin).okData().path("employeeNo").asText();
    }

    private void setStatus(String admin, String employeeNo, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("employeeNo", employeeNo);
        m.put("name", "停用回归-" + employeeNo);
        m.put("roleNo", "OPS");
        m.put("status", status);
        post("/api/platform/employees", m, admin).okData();
    }

    @Test
    @DisplayName("★★ 停用之后，他手里的旧令牌当场失效")
    void a_disabled_employee_loses_the_token_he_already_holds() {
        String admin = login("ADMIN");
        String no = createActiveEmployee(admin, uniqueNo());

        // 用 employee_no 登录 —— EmployeeRoles.rolesOf 就是拿登录名当 employee_no 查的
        String token = login("OPS", no, null);

        // 前置：停用**之前**这张令牌是好用的。
        // 不验这一步的话，下面的 401 可能只是「这个令牌从来就没好用过」。
        assertThat(get("/api/auth/menus", token).status)
                .as("前提：停用前旧令牌可用")
                .isEqualTo(200);

        setStatus(admin, no, "LEFT");

        assertThat(get("/api/auth/menus", token).status)
                .as("停用后旧令牌必须失效 —— 这正是补 B3 之前漏掉的那一下")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("失效是一次性的：令牌已被撤销，第二次请求连会话都查不到")
    void the_token_is_revoked_not_merely_rejected() {
        String admin = login("ADMIN");
        String no = createActiveEmployee(admin, uniqueNo());
        String token = login("OPS", no, null);
        setStatus(admin, no, "LEFT");

        // 第一次请求发现失效并撤销令牌；第二次不该再查一遍库、再判一次
        assertThat(get("/api/auth/menus", token).status).isEqualTo(401);
        assertThat(get("/api/auth/menus", token).status)
                .as("撤销之后第二次请求同样 401（而且是在 tokenStore.get 就没了）")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("★ 这道闸不许误伤：bump 之后别人的会话照常")
    void other_sessions_survive_the_bump() {
        String admin = login("ADMIN");

        // admin 这个登录名**不是**任何 employee_no —— 生产与集成测试都是这个形态。
        // 「查不到就判失效」的写法会在下一行把它锁死。
        assertThat(get("/api/auth/menus", admin).status).isEqualTo(200);

        String no = createActiveEmployee(admin, uniqueNo());
        String victim = login("OPS", no, null);
        setStatus(admin, no, "LEFT");               // 这一步会 bump，全体会话戳失配

        assertThat(get("/api/auth/menus", victim).status)
                .as("被停用的那个：失效").isEqualTo(401);
        assertThat(get("/api/auth/menus", admin).status)
                .as("认不出员工的登录名（admin）：照常放行").isEqualTo(200);
    }

    @Test
    @DisplayName("在职员工被 bump 之后也照常 —— 重建不是把人都赶出去")
    void an_active_employee_survives_the_bump() {
        String admin = login("ADMIN");
        String keep = createActiveEmployee(admin, uniqueNo());
        String token = login("OPS", keep, null);

        // 停用**另一个**人，触发全局 bump
        setStatus(admin, createActiveEmployee(admin, uniqueNo()), "LEFT");

        assertThat(get("/api/auth/menus", token).status)
                .as("在职的这位不该被连累").isEqualTo(200);
    }
}
