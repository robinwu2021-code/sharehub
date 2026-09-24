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
    void the_whole_tree_is_delivered() {
        JsonNode t = tree(login("ADMIN"));
        assertThat(t.size()).as("顶级菜单数").isEqualTo(18);
        int leaves = 0;
        for (JsonNode s : t) leaves += s.path("children").size();
        assertThat(leaves).as("叶子数").isEqualTo(109);
    }

    @Test
    void the_six_new_columns_survive_the_whole_chain() {
        // 实体没映射 / DTO 没带 / JSON 没序列化 —— 三处任一漏掉都只是「少一种修饰」，不报错
        JsonNode t = tree(login("ADMIN"));
        JsonNode operation = null, myBiz = null;
        for (JsonNode s : t) {
            if ("M_operation".equals(s.path("menuNo").asText())) operation = s;
            if ("M_my-biz".equals(s.path("menuNo").asText())) myBiz = s;
        }
        assertThat(operation).as("前提：运营管理在树里").isNotNull();
        assertThat(myBiz).as("前提：代理门户在树里").isNotNull();

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
    void the_tree_is_not_filtered_by_permission() {
        /*
         * 后端**刻意不按 perm 剪枝**：前端的 can() 会先经 UI_PERM_MAP 把界面码
         * 翻译成后端码再判（目前 8 条），后端没有这层翻译。在这里剪枝会把
         * 带翻译码的叶子剪掉，而前端本来是显示的 —— 菜单少一项，不报错。
         * 可见性只由前端那一套规则决定（它还要处理 module/portalFor/phase/ready）。
         */
        assertThat(tree(login("VIEWER")).size())
                .as("只读角色拿到的树与管理员一样大")
                .isEqualTo(tree(login("ADMIN")).size());
    }

    @Test
    void anonymous_cannot_read_the_menu() {
        // 树里只有菜单名与路径，不是业务数据；但也没有理由对未登录者下发
        assertThat(get("/api/auth/menus", null).status).isEqualTo(401);
    }
}
