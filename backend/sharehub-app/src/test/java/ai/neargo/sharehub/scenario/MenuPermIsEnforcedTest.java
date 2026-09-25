package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **库里每个菜单挂的权限码，后端都必须真的在强制它。**
 *
 * <h2>这条与 MenuPermissionContractTest 不同在哪</h2>
 * 那一条扫的是 <b>源码</b>（{@code ops-web/lib/nav.ts}）。P4 之后菜单的 {@code perm}
 * <b>可以在界面上改</b> —— 库里的值不再必然等于源码里的值，源码扫描看不见这种漂移。
 * 这一条读的是<b>库</b>（经 {@code GET /api/platform/iam/menus} 的全量树）。
 *
 * <h2>挂错了会怎样</h2>
 * 目录 {@code iam_permission} 里有 17 条「登记了但没有任何端点强制」的码
 * （12 条通配 + 5 条只在界面上用的）。菜单管理的入口只校验「码在目录里」，
 * 所以这 17 条是挂得上去的 —— 挂上之后<b>只有超管看得到这个菜单</b>，
 * 别人既看不到入口、也不会收到任何报错：配的人以为配好了，用的人以为功能没做。
 *
 * <p>通配码（{@code finance:*}）算强制：持有它的人确实能调那一族端点。
 */
class MenuPermIsEnforcedTest extends ApiTestSupport {

    private static final Path BACKEND = Path.of("..");
    private static final Pattern PERM = Pattern.compile("@perm\\.can\\('([^']+)'\\)");

    /** 源码里所有被 @PreAuthorize 强制的码。 */
    private static Set<String> enforced() throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(BACKEND)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/target/"))
                    .filter(f -> !f.toString().contains("/src/test/"))
                    .toList()) {
                Matcher m = PERM.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) out.add(m.group(1));
            }
        }
        return out;
    }

    /** 通配也算：持 `finance:*` 的人确实调得动 finance 那一族。 */
    private static boolean covered(Set<String> enforced, String code) {
        if (enforced.contains(code)) return true;
        if (code.endsWith(":*")) {
            String prefix = code.substring(0, code.length() - 1);
            return enforced.stream().anyMatch(e -> e.startsWith(prefix));
        }
        return false;
    }

    @Test
    void every_menu_perm_is_actually_enforced_somewhere() throws IOException {
        Set<String> enforced = enforced();
        assertThat(enforced).as("前提：扫得到 @perm.can").isNotEmpty();

        List<String> dangling = new ArrayList<>();
        for (JsonNode s : get("/api/platform/iam/menus", login("ADMIN")).okData()) {
            check(enforced, dangling, s);
            for (JsonNode c : s.path("children")) check(enforced, dangling, c);
        }
        assertThat(dangling).as("""
                这些菜单挂的码**没有任何端点在强制**。后果是只有超管看得到它们，
                而别人既看不到入口、也收不到任何报错 ——
                配的人以为配好了，用的人以为功能没做。

                多半是在「菜单管理」里从目录挑了一条「登记了但没人用」的码
                （目录里有 17 条是这样：12 条通配 + 5 条只在界面上用的）。""")
                .isEmpty();
    }

    private static void check(Set<String> enforced, List<String> out, JsonNode n) {
        String perm = n.path("perm").asText("");
        if (!perm.isBlank() && !covered(enforced, perm)) {
            out.add(n.path("menuNo").asText() + "（" + n.path("name").asText() + "）→ " + perm);
        }
    }
}
