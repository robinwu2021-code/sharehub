package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单管理：**改「怎么显示、谁看得到」，不改「指向哪」。**
 *
 * <h2>这块为什么危险</h2>
 * 菜单是运营端<b>唯一</b>的入口。把「员工与权限」那一支藏了之后，
 * 谁都没法再进来把它改回去 —— 只能改库。所以护栏比功能本身更要紧。
 */
class MenuAdminTest extends ApiTestSupport {

    private static final String MENUS = "/api/platform/iam/menus";

    private JsonNode node(String admin, String menuNo) {
        for (JsonNode s : get(MENUS, admin).okData()) {
            if (menuNo.equals(s.path("menuNo").asText())) return s;
            for (JsonNode c : s.path("children")) {
                if (menuNo.equals(c.path("menuNo").asText())) return c;
            }
        }
        return null;
    }

    private Map<String, Object> patch(String key, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, v);
        return m;
    }

    @Test
    void the_full_tree_is_for_management_not_for_rendering() {
        // 与 /api/auth/menus 的分工：那个是「我看得到的」，这个是「全部」——
        // 菜单管理要能看到、也能改管理员自己都看不到的项
        String admin = login("ADMIN");
        assertThat(get(MENUS, admin).okData().size()).as("全量 section 数（含代理门户）").isEqualTo(18);
    }

    @Test
    void renaming_takes_effect_without_a_deploy() {
        // 这正是整件事要换来的东西：改个菜单名不用发版
        String admin = login("ADMIN");
        String before = node(admin, "M_device").path("name").asText();
        try {
            put(MENUS + "/M_device", patch("name", "设备中心"), admin).okData();
            assertThat(node(admin, "M_device").path("name").asText()).isEqualTo("设备中心");
        } finally {
            put(MENUS + "/M_device", patch("name", before), admin).okData();
        }
    }

    @Test
    void a_permission_code_outside_the_catalog_is_refused() {
        /*
         * 挂一个没人强制的码，等于这个菜单对谁都不可见（除超管），而**不报错**：
         * 配的人以为配好了，用的人以为功能没做。所以在入口拦。
         */
        String admin = login("ADMIN");
        assertThat(put(MENUS + "/M_device", patch("perm", "device:nonexistent:read"), admin).status)
                .isEqualTo(400);
        assertThat(node(admin, "M_device").path("perm").asText(null))
                .as("拒了就不该落库").isNotEqualTo("device:nonexistent:read");
    }

    @Test
    void you_cannot_lock_yourself_out() {
        /*
         * **本文件最重要的一条。** 把「员工与权限」藏掉之后，
         * 连「进来改回去」的入口都没有了 —— 只能改库。所以必须在事务里拦住并回滚。
         */
        String admin = login("ADMIN");
        assertThat(put(MENUS + "/M_org", patch("visible", 0), admin).status)
                .as("藏掉自己那一支必须被拒").isEqualTo(500);

        assertThat(node(admin, "M_org")).as("而且要回滚——它还得在").isNotNull();
        assertThat(get("/api/auth/menus", admin).okData().toString())
                .as("超管仍然进得来").contains("M_org");
    }

    @Test
    void hiding_an_ordinary_menu_is_allowed_and_really_hides_it() {
        // 护栏只保「回得来的那扇门」，别的该藏就能藏 —— 否则这个功能等于没有
        String admin = login("ADMIN");
        try {
            put(MENUS + "/M_marketing", patch("visible", 0), admin).okData();
            assertThat(get("/api/auth/menus", admin).okData().toString())
                    .as("藏了就不该出现在「我看得到的」那棵树里")
                    .doesNotContain("M_marketing");
            assertThat(node(admin, "M_marketing"))
                    .as("但管理用的全量树里还在——否则藏了就再也找不回来").isNotNull();
        } finally {
            put(MENUS + "/M_marketing", patch("visible", 1), admin).okData();
        }
    }

    @Test
    void a_missing_menu_is_refused_rather_than_silently_creating_one() {
        String admin = login("ADMIN");
        assertThat(put(MENUS + "/M_does_not_exist", patch("name", "凭空"), admin).status)
                .isEqualTo(400);
    }
}
