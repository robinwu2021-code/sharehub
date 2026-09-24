package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **员工级数据范围**（A3）：同一个角色下，某个人可以看得比角色更窄或更宽。
 *
 * <p>写入口（{@code PUT /api/platform/data-scopes/EMPLOYEE/{no}}）此前就在，
 * 而**没有任何地方读它** —— {@code PermissionService} 只查
 * {@code subject_type='ROLE'} 的行。于是配置保存成功、库里也有，
 * 而那个人看到的还是角色那一份：**每一步都显示正常**，最难查的那一类。
 *
 * <p>按 {@code userNo} 认人（会话的 {@code LoginUser.userNo}）。
 * dev-mode 下用户名即 userNo，所以这里用**站点编号当用户名**登录，
 * 刻意让它等于我们配了范围的那个 subjectNo。
 *
 * @see ai.neargo.sharehub.platform.iam.PermissionService#scopeOf
 */
class EmployeeDataScopeTest extends ApiTestSupport {

    private static final String WHO = "E-SCOPE-TEST";

    private void setScope(String admin, String subjectType, String no, String type, String refs) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("scopeType", type);
        body.put("scopeRefs", refs);
        put("/api/platform/data-scopes/" + subjectType + "/" + no, body, admin).okData();
    }

    private Set<String> siteNosVisibleTo(String token) {
        Set<String> out = new HashSet<>();
        get("/api/ops/sites?page=1&size=500", token).okData().path("list")
                .forEach(s -> out.add(s.path("siteNo").asText()));
        return out;
    }

    @Test
    void an_employee_can_be_narrowed_below_their_role() {
        String admin = login("ADMIN");

        // VIEWER 角色本身不受限（库里没有 ROLE 行 → ALL），先确认这个前提
        String token = login("VIEWER", WHO, null);
        Set<String> whole = siteNosVisibleTo(token);
        assertThat(whole).as("前提：这个角色本来看得到站点").isNotEmpty();
        assertThat(whole.size()).as("前提：不止一个站点，收窄才验得出来").isGreaterThan(1);

        String only = whole.iterator().next();
        setScope(admin, "EMPLOYEE", WHO, "SITE", only);
        try {
            // 同一个 token，不重登 —— 保存时 permVersion.bump() 应让下一请求就重算
            assertThat(siteNosVisibleTo(token))
                    .as("配了员工级范围，这个人就只该看到那一个站点")
                    .containsExactly(only);
        } finally {
            setScope(admin, "EMPLOYEE", WHO, "ALL", "");
        }
    }

    @Test
    void employee_scope_overrides_the_role_scope_instead_of_intersecting_it() {
        // 「更宽」是这个功能存在的一半理由。取交集的话永远实现不了，
        // 而管理员只会看到「我明明配了 ALL，他还是只看得到那几个」。
        String admin = login("ADMIN");
        /*
         * 用户名必须每次都是新的：本用例的前半段要证明「角色收窄生效」，
         * 前提是这个人**还没有**员工级的行。而收尾只能把它改成 ALL（没有删除端点），
         * 于是第二次跑时那条 ALL 会盖掉角色的收窄 —— 用例第一次绿、之后一直红，
         * 而红的样子像是功能坏了。测试库是累积共享的，这类「上一次自己留下的状态」
         * 是本仓库反复踩的坑。
         */
        // 取模截短：审计表的 action 列存的是 URI，名字太长会让这条操作的留痕被截断而写不进去
        String who = WHO + "-W" + System.nanoTime() % 100000;
        String token = login("VIEWER", who, null);
        Set<String> whole = siteNosVisibleTo(token);
        assertThat(whole.size()).isGreaterThan(1);
        String one = whole.iterator().next();

        setScope(admin, "ROLE", "VIEWER", "SITE", one);
        try {
            assertThat(siteNosVisibleTo(token)).as("先让角色收窄").containsExactly(one);

            setScope(admin, "EMPLOYEE", who, "ALL", "");
            assertThat(siteNosVisibleTo(token))
                    .as("员工级 ALL 要盖过角色级的收窄")
                    .hasSameSizeAs(whole);
        } finally {
            // 角色级那条一定要还原：测试库是共享累积的，留着会让后面所有
            // VIEWER 相关的用例都在一个被收窄过的范围上跑。
            setScope(admin, "ROLE", "VIEWER", "ALL", "");
        }
    }

    @Test
    void self_scope_no_longer_means_everything() {
        /*
         * 「仅自己经手」此前解析出来**等于全部数据** —— SELF 落库时 refs 被清空，
         * 而解析时空 refs 的规则被滤掉，滤光了就退回 ALL。方向恰好反了，
         * 且没有任何地方会报错：管理员以为收到了最紧，实际放到了最松。
         *
         * 现在 SELF 带上本人的号。运营台的表都没有登记 SELF 锚点，
         * 于是 DataScopeHandler 按 fail-closed 拼成 1=0 —— 错也错在安全那一侧。
         */
        String admin = login("ADMIN");
        String token = login("VIEWER", WHO + "-SELF", null);
        assertThat(siteNosVisibleTo(token)).as("前提：本来看得到").isNotEmpty();

        setScope(admin, "EMPLOYEE", WHO + "-SELF", "SELF", "");
        try {
            assertThat(siteNosVisibleTo(token))
                    .as("SELF 绝不能等于 ALL")
                    .isEmpty();
        } finally {
            setScope(admin, "EMPLOYEE", WHO + "-SELF", "ALL", "");
        }
    }

    @Test
    void reading_back_what_was_saved() {
        // 前端抽屉打开时要拿回已配的值。读不回来的话，每次打开都是空的，
        // 一保存就把原来的范围覆盖成默认值 —— 而这个过程全程不报错。
        String admin = login("ADMIN");
        setScope(admin, "EMPLOYEE", WHO + "-READ", "SITE", "ST301,ST302");
        JsonNode got = get("/api/platform/data-scopes/EMPLOYEE/" + WHO + "-READ", admin).okData();
        assertThat(got.path("scopeType").asText()).isEqualTo("SITE");
        assertThat(got.path("scopeRefs").asText()).isEqualTo("ST301,ST302");
        assertThat(got.path("subjectType").asText()).isEqualTo("EMPLOYEE");
    }
}
