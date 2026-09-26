package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 区域字典必须有叶子层（level=3）。
 *
 * <h2>为什么这条值得有测试</h2>
 * {@code md_region} 一行都没有，而「新增站点」的区域是**必填**、且只列 level=3
 * （挂到「阿联酋」这种国家节点等于没收敛数据权限）——
 * 于是那个下拉永远是空的，**站点在真后端建不出来**。
 *
 * <p>本地跑 mock 看不出来：{@code ops-web/lib/mock/db/system.ts} 里一直有那 12 行。
 * 今天已经是第四次撞见同一个形状（mock 比真后端富：次卡、代理账号数据范围、
 * 员工部门、这条）。所以这里断言的不是「表里有行」，而是
 * **表单真正用得上的那一层有行** —— 只有 level 1/2 的字典和空字典对这张表单是同一回事。
 *
 * <p>没有断言具体是哪几个区：那是运营数据，会随开城增减。
 */
class RegionDictionarySeedTest extends ApiTestSupport {

    @Test
    @DisplayName("★★ 区域字典要有 level=3 的叶子层——否则新增站点的区域下拉永远是空的")
    void regionDictionaryHasLeafLevel() {
        String admin = login("ADMIN");
        JsonNode body = get("/api/platform/regions?page=1&size=500", admin).okData();

        var all = body.path("list");
        assertThat(all.size()).as("区域字典不能是空的").isPositive();

        long leaves = 0;
        for (JsonNode r : all) {
            if (r.path("level").asInt() == 3) leaves++;
        }
        assertThat(leaves)
                .as("必须有 level=3 的叶子层 —— 站点表单只列这一层（见 operation/sites 的 regionOpts）")
                .isPositive();
    }

    @Test
    @DisplayName("叶子层的上级要真的存在——parentId 指向不存在的区等于树断了")
    void leafRegionsHaveRealParents() {
        String admin = login("ADMIN");
        JsonNode all = get("/api/platform/regions?page=1&size=500", admin).okData().path("list");

        var ids = new java.util.HashSet<String>();
        all.forEach(r -> ids.add(r.path("regionId").asText()));

        var orphans = new java.util.ArrayList<String>();
        for (JsonNode r : all) {
            String parent = r.path("parentId").asText("");
            if (r.path("level").asInt() > 1 && !parent.isBlank() && !ids.contains(parent)) {
                orphans.add(r.path("regionId").asText() + " → " + parent);
            }
        }
        assertThat(orphans)
                .as("上级不存在的区域（组织树会少一截，按区域收敛的数据权限也跟着错）")
                .isEmpty();
    }
}
