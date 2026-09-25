package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单树下发（V67：真源从 ops-web/lib/nav.ts 迁到 iam_menu）。
 *
 * <p>「迁移里的 127 行 = nav.ts」由前端卡口 {@code nav-seed.test.ts} 逐字段守；
 * 这里守的是**另一半**：迁移跑完之后，端点真的把这 127 行按树发出去，
 * 且六个新列没有在 实体→DTO→JSON 这条链上的任何一环悄悄掉队。
 *
 * <p>漏一个字段的表现不是报错，是**前端少一种修饰** ——
 * 分组标题没了、代理端门户失效、某个叶子该灰的没灰。
 */
class MenuTreeTest extends ApiTestSupport {

    private JsonNode tree(String token) {
        return get("/api/auth/menus", token).okData();
    }

    @Test
    void admin_sees_every_operations_menu() {
        /*
         * ADMIN 持 `*`，所以他看得到**除代理门户外**的全部 ——
         * 门户是排他的（ADR-012：代理只看门户，运营看不到门户），
         * 所以是 15 个 section 而不是 18。
         *
         * 端点从 P1 起**按权限剪枝**（此前下发完整树、前端自己筛）。
         * 要全量树用 MenuService.tree()，那是给菜单管理界面留的。
         */
        JsonNode t = tree(login("ADMIN"));
        assertThat(t.size()).as("运营侧 section 数（18 个里 3 个是代理门户）").isEqualTo(15);
        int leaves = 0;
        for (JsonNode s : t) leaves += s.path("children").size();
        assertThat(leaves).as("ADMIN 看得到的叶子数").isEqualTo(103);
    }

    @Test
    void the_six_new_columns_survive_the_whole_chain() {
        // 实体没映射 / DTO 没带 / JSON 没序列化 —— 三处任一漏掉都只是「少一种修饰」，不报错
        // 门户只在代理的树里，运营的树里没有 —— 所以两个角色各取一棵
        JsonNode operation = null, myBiz = null;
        for (JsonNode s : tree(login("ADMIN"))) {
            if ("M_operation".equals(s.path("menuNo").asText())) operation = s;
        }
        for (JsonNode s : tree(login("AGENT"))) {
            if ("M_my-biz".equals(s.path("menuNo").asText())) myBiz = s;
        }
        assertThat(operation).as("前提：运营管理在运营的树里").isNotNull();
        assertThat(myBiz).as("前提：代理门户在代理的树里").isNotNull();

        assertThat(operation.path("module").asText()).isNotBlank();
        assertThat(operation.path("modules")).as("跨模块 section 的 modules 是数组").hasSize(5);
        assertThat(operation.path("match")).as("路径归属前缀").hasSize(2);
        assertThat(myBiz.path("portalFor")).as("专属门户角色").hasSize(1);
        assertThat(myBiz.path("portalFor").get(0).asText()).isEqualTo("AGENT");
    }

    @Test
    void leaf_level_fields_survive_too() {
        JsonNode t = tree(login("ADMIN"));
        boolean sawGroup = false, sawReady = false, sawPhase2 = false;
        for (JsonNode s : t) {
            for (JsonNode c : s.path("children")) {
                if (!c.path("group").isNull() && !c.path("group").asText().isBlank()) sawGroup = true;
                if (c.path("ready").asBoolean()) sawReady = true;
                if (c.path("phase").asInt() > 1) sawPhase2 = true;
            }
        }
        assertThat(sawGroup).as("L2 分组标题").isTrue();
        assertThat(sawReady).as("ready 解锁标记（nav.ts 里有 8 处）").isTrue();
        assertThat(sawPhase2).as("分期标记").isTrue();
    }

    @Test
    void a_narrower_role_gets_a_smaller_tree() {
        /*
         * P1 起服务端按权限剪枝。此前这条用例断言的恰好相反
         * （「只读角色拿到的树与管理员一样大」），理由是「后端没有 UI_PERM_MAP
         * 翻译层，过滤会误剪」—— **该理由经实测对菜单不成立**：
         * 109 个带码的菜单叶，需要翻译的是 0 个，那 8 条翻译全服务于页内按钮。
         *
         * 只断言「更小」而不写死数字：具体几项由角色权限决定，
         * 钉死的那份逐项清单在 MenuVisibilityParityTest（与前端快照对拍）。
         */
        int viewer = tree(login("VIEWER")).size();
        int admin = tree(login("ADMIN")).size();
        assertThat(viewer).as("只读角色看得到的 section 比管理员少").isLessThan(admin);
        assertThat(viewer).as("但不该是空的").isGreaterThan(0);
    }

    @Test
    void anonymous_cannot_read_the_menu() {
        // 树里只有菜单名与路径，不是业务数据；但也没有理由对未登录者下发
        assertThat(get("/api/auth/menus", null).status).isEqualTo(401);
    }
}
