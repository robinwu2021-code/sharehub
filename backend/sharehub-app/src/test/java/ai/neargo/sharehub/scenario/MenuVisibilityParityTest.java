package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **服务端算出的菜单，必须与前端算出的逐项相同。**
 *
 * <h2>为什么要两套实现对拍</h2>
 * P1 把菜单可见性的判定从前端（{@code visibleSections}/{@code visibleLeaves}）
 * 挪到了服务端（{@code MenuService.visibleFor}）。**判定换地方最容易出的事，
 * 是新旧两套规则不等价** —— 某个角色悄悄多出或少掉几项，而这两种都不报错：
 * 多出来的点进去 403，少掉的让人以为功能没做。
 *
 * <p>所以不比「服务端自己前后一致」，而是拿服务端的结果去比
 * **前端提交在仓库里的那份快照** {@code ops-web/lib/nav-visibility.snapshot.txt}。
 * 两套独立实现 × 一份提交物：任何一边先漂都会红。
 *
 * <h2>红了怎么办</h2>
 * 差异会逐项列出（`角色: section›叶子`）。先判断是哪一边错：
 * <ul>
 *   <li>服务端多给了 → {@code MenuService} 的规则比前端松，会给出点下去 403 的入口；</li>
 *   <li>服务端少给了 → 比前端紧，用的人会以为功能没做；</li>
 *   <li>确实是有意的产品变更 → 前端快照与本用例一起更新，两边的 diff 都要进同一个提交。</li>
 * </ul>
 *
 * <h2>为什么用 dev-mode 的角色登录</h2>
 * 真实员工登录（读 {@code iam_employee} + {@code iam_employee_role}）是 P3 才打通的。
 * 在那之前 dev-mode 按角色发 token 是唯一能逐角色验证的方式，
 * 而角色→权限码这一段（{@code iam_role_perm}）本来就是真的。
 */
class MenuVisibilityParityTest extends ApiTestSupport {

    /** 前端那份快照。测试的工作目录是 backend/sharehub-app。 */
    private static final Path SNAPSHOT =
            Path.of("..", "..", "ops-web", "lib", "nav-visibility.snapshot.txt");

    /** 快照 → {角色: [section›叶子, …]}。 */
    private static Map<String, List<String>> fromSnapshot() throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String role = null;
        for (String line : Files.readAllLines(SNAPSHOT, StandardCharsets.UTF_8)) {
            if (line.startsWith("## ")) {
                role = line.substring(3).split("（")[0].trim();
                out.put(role, new ArrayList<>());
            } else if (!line.isBlank() && !line.startsWith("#") && role != null) {
                out.get(role).add(line.trim());
            }
        }
        return out;
    }

    /** 服务端的树 → 同样的 `section›叶子` 形状。菜单号形如 M_<key>，与前端的 section.key 对齐。 */
    private List<String> fromApi(String role) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : get("/api/auth/menus", login(role)).okData()) {
            String key = s.path("menuNo").asText().replaceFirst("^M_", "");
            for (JsonNode c : s.path("children")) {
                out.add(key + "›" + c.path("name").asText());
            }
        }
        return out;
    }

    @Test
    void every_role_sees_exactly_what_the_frontend_computes() throws IOException {
        Map<String, List<String>> want = fromSnapshot();
        assertThat(want).as("前提：读得到前端快照").isNotEmpty();

        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : want.entrySet()) {
            String role = e.getKey();
            TreeSet<String> expected = new TreeSet<>(e.getValue());
            TreeSet<String> actual = new TreeSet<>(fromApi(role));

            for (String x : expected) if (!actual.contains(x)) diffs.add(role + ": 服务端少了 " + x);
            for (String x : actual) if (!expected.contains(x)) diffs.add(role + ": 服务端多了 " + x);
        }
        assertThat(diffs).as("""
                服务端与前端算出的菜单不一致。两种都不报错、都很难查：
                  · 服务端多给 → 用户点进去 403；
                  · 服务端少给 → 用户以为功能没做。
                确认是有意的产品变更，才连同 ops-web/lib/nav-visibility.snapshot.txt 一起更新。""")
                .isEmpty();
    }

    @Test
    void the_agent_portal_is_exclusive_in_both_directions() {
        // 代理商是运营方体内的受限外部伙伴（ADR-012）：
        // 他只看得到门户，而运营角色**看不到**门户。此前靠「无 perm 的叶子跟随父模块」
        // 漏出过 SLA 管理 / 巡检计划 / BD 拓展 CRM —— 两个方向都错过。
        List<String> agent = fromApi("AGENT");
        assertThat(agent).as("代理商看得到门户").isNotEmpty();
        assertThat(agent).allSatisfy(x ->
                assertThat(x).as("代理商只该看到门户 section").startsWith("my-"));

        assertThat(fromApi("ADMIN")).as("运营角色看不到代理门户")
                .noneSatisfy(x -> assertThat(x).startsWith("my-"));
    }
}
