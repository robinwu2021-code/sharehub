package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **改了权限，当事人不重登也要收敛**（口径 B）。
 *
 * <p>这是 ops-web 接 {@code GET /api/auth/me} 刷新 perms 的**前提**：
 * 若该端点返回的是登录那一刻的快照，前端再怎么定时拉也只是把同一份旧权限
 * 反复写回 store —— 看起来接上了，实际什么都没变。
 *
 * <p>口径 B 的实现在 {@code StaffTokenAuthFilter.onSession}：会话戳 ≠ 全局
 * {@code PermVersion} 时按会话角色重建。这条链此前**一个测试都没有** ——
 * 而它同时决定两件事：服务端判权用哪份权限，以及 {@code /me} 下发哪份。
 *
 * <p>拿 {@code CUSTOM} 角色做：内置角色 {@code builtin=1} 改不动，
 * 而 {@code IamSeeder} 正是为「改权限 + 口径 B 在线生效验证」留的它。
 */
class PermsRefreshWithoutReloginTest extends ApiTestSupport {

    private static final String ROLE = "CUSTOM";
    private static final String PERMS_PATH = "/api/platform/iam/roles/" + ROLE + "/permissions";

    private List<String> permsOf(String token) {
        List<String> out = new ArrayList<>();
        get("/api/auth/me", token).okData().path("perms").forEach(p -> out.add(p.asText()));
        return out;
    }

    private void setPerms(String admin, List<String> perms) {
        put(PERMS_PATH, Map.of("perms", perms), admin).okData();
    }

    @Test
    void me_reflects_permission_changes_without_a_new_login() {
        String admin = login("ADMIN");
        List<String> original = new ArrayList<>();
        get(PERMS_PATH, admin).okData().forEach(p -> original.add(p.asText()));
        assertThat(original).as("前提：CUSTOM 角色有权限行").isNotEmpty();

        // 这个 token 全程不换 —— 整条用例的意义就在于「同一个会话」
        String token = login(ROLE, "custom.user", null);
        assertThat(permsOf(token)).as("前提：/me 认这个会话").isNotEmpty();

        try {
            // ——— 收权：给的比原来少 ———
            setPerms(admin, List.of("dashboard:overview:read"));
            assertThat(permsOf(token))
                    .as("收了权，不重登也要少掉")
                    .containsExactly("dashboard:overview:read");

            // ——— 授权：给一个原来没有的 ———
            setPerms(admin, List.of("dashboard:overview:read", "order:order:read"));
            assertThat(permsOf(token))
                    .as("新授的权，不重登也要拿得到")
                    .containsExactlyInAnyOrder("dashboard:overview:read", "order:order:read");
        } finally {
            // 测试库是累积共享的：不还原的话，CUSTOM 从此带着本用例的权限，
            // 后面所有用它的用例都在一份被本用例改过的数据上跑。
            setPerms(admin, original);
        }
    }

    @Test
    void revoked_permission_is_refused_by_the_server_not_just_hidden_in_the_ui() {
        // 前端隐藏按钮只是体验；**闸在服务端**。这两件事必须分开验：
        // 只验 /me 的话，「界面收敛了但接口照样放行」会完整地漏过去。
        String admin = login("ADMIN");
        List<String> original = new ArrayList<>();
        get(PERMS_PATH, admin).okData().forEach(p -> original.add(p.asText()));

        String token = login(ROLE, "custom.user2", null);
        try {
            setPerms(admin, List.of("dashboard:overview:read", "order:order:read"));
            assertThat(get("/api/trade/orders?page=1&size=1", token).status)
                    .as("有权时放行").isEqualTo(200);

            setPerms(admin, List.of("dashboard:overview:read"));
            assertThat(get("/api/trade/orders?page=1&size=1", token).status)
                    .as("收权后同一个 token 必须被拒").isEqualTo(403);
        } finally {
            setPerms(admin, original);
        }
    }

    @Test
    void me_never_500s_on_a_missing_field() {
        /*
         * /me 曾用 Map.of 拼响应 —— 它遇到任何一个 null 值就抛 NPE → 500。
         * CurrentUser.system() 的 agentNo 就是 null。
         *
         * 这条之所以值得单独守：前端的 perms-sync 会**吞掉**这个异常
         * （一次刷新失败不该把人弹回登录页，那是对的），于是 500 的表现是
         * 「权限从此不再刷新」，而界面上一点异常都没有。
         */
        String token = login(ROLE, "custom.shape", null);
        JsonNode me = get("/api/auth/me", token).okData();
        assertThat(me.path("agentNo").isMissingNode()).as("字段要在，哪怕是空串").isFalse();
        assertThat(me.path("perms").isArray()).as("perms 必须是数组").isTrue();
        assertThat(me.path("username").asText()).isNotBlank();
    }

    @Test
    void me_says_not_authenticated_for_a_revoked_session() {
        // 前端按这个字段判「该回登录页了」。返回 200 + authenticated=false
        // 与返回 401 是两条不同的前端分支，必须知道实际走的是哪条。
        String token = login(ROLE, "custom.user3", null);
        JsonNode me = get("/api/auth/me", token).okData();
        assertThat(me.path("authenticated").asBoolean()).isTrue();

        post("/api/auth/logout", Map.of(), token).okData();
        assertThat(get("/api/auth/me", token).status)
                .as("吊销后 /me 的 HTTP 状态（401 走 sessionExpired，200 才看 authenticated）")
                .isEqualTo(401);
    }
}
