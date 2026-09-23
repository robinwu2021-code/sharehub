package ai.neargo.sharehub.arch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单 × 页面 × 后端权限码 的三方契约。
 *
 * <h2>这类缺陷为什么谁都发现不了</h2>
 * 菜单可见性用 {@code can(role, perm)} 过滤（{@code ops-web/lib/nav.ts}），
 * 接口鉴权用 {@code @PreAuthorize("@perm.can(...)")}。
 * <b>两边各查各的码，没有任何东西要求它们一致</b>。表现只有两种：
 * <ul>
 *   <li><b>看得见点不开</b> —— 菜单码有、接口码没有，点进去 403；</li>
 *   <li><b>能用但找不到</b> —— 接口码有、菜单上无入口。</li>
 * </ul>
 * 两种都不报错。功能权限清单 §末 记录的第 2、3 条问题正是如此 ——
 * <b>文档里在案一年，代码里没有任何东西会红</b>。
 *
 * <h2>守三条</h2>
 * <ol>
 *   <li>菜单叶指向的页面必须存在（否则点了 404）；</li>
 *   <li>菜单声明的权限码必须有后端端点使用它 —— 台账外的一律失败；</li>
 *   <li>后端的资源必须在菜单上有入口（按 {@code 模块:资源} 归组，
 *       因为菜单叶声明的一律是 {@code :read}，而 {@code :create} 是页内动作的码）。</li>
 * </ol>
 *
 * <p>可见性按前端的真实规则算：section 的 {@code module}（{@code canModule}）
 * 与叶子的 {@code perm}（{@code can}）两层，缺一层就会误报整个模块。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class MenuPermissionContractTest {

    /** 对象字面量里同时带 href 与 label 的才是叶子（section 头有 href 但没 label 在同一层）。 */
    private static final Pattern LEAF = Pattern.compile("\\{([^{}]*href\\s*:[^{}]*)\\}");
    private static final Pattern HREF = Pattern.compile("href\\s*:\\s*[`\"']([^`\"']+)");
    private static final Pattern LABEL = Pattern.compile("label\\s*:\\s*[\"']([^\"']+)");
    private static final Pattern PERM = Pattern.compile("perm\\s*:\\s*[\"']([^\"']+)");
    /** 运营管理那组由 opLeaves([[page, label, perm, group], …]) 动态生成。 */
    private static final Pattern OP_BLOCK = Pattern.compile("opLeaves\\(\\[(.*?)\\]\\s*\\)", Pattern.DOTALL);
    private static final Pattern OP_ROW =
            Pattern.compile("\\[\\s*\"([\\w-]+)\"\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*\\]");
    private static final Pattern MODULE = Pattern.compile("\\bmodule\\s*:\\s*[\"']([\\w-]+)[\"']");
    private static final Pattern MODULES = Pattern.compile("\\bmodules\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern QUOTED = Pattern.compile("[\"']([\\w-]+)[\"']");

    private record Leaf(String href, String label, String perm) {
    }

    @Test
    @DisplayName("菜单叶指向的页面都存在（否则点了 404）")
    void everyMenuLeafHasAPage() throws IOException {
        List<Leaf> leaves = leaves();
        Set<String> routes = routes();

        assertThat(leaves).as("应当解析到菜单叶；一条都没有说明 nav.ts 解析坏了").isNotEmpty();
        assertThat(routes).as("应当找到页面").isNotEmpty();

        List<String> bad = new ArrayList<>();
        for (Leaf lf : leaves) {
            String p = lf.href().split("\\?")[0].replaceAll("/+$", "");
            if (p.isEmpty()) p = "/";
            if (!routes.contains(p)) bad.add(lf.href() + "（" + lf.label() + "）");
        }
        assertThat(bad).as("菜单上有、页面不存在 —— 用户点了就是 404").isEmpty();
    }

    @Test
    @DisplayName("菜单权限码都有后端端点使用（台账只准变短）")
    void everyMenuPermIsEnforcedSomewhere() throws IOException {
        Set<String> beperms = backendPerms();
        assertThat(beperms).as("contract.json 应当有权限码").isNotEmpty();

        Set<String> orphan = new TreeSet<>();
        for (Leaf lf : leaves()) {
            if (lf.perm() != null && !beperms.contains(lf.perm())) orphan.add(lf.perm());
        }
        Set<String> known = readLedger(backendRoot().resolve("known-menu-perm-mismatch.txt"));

        assertThat(orphan)
                .as("菜单用这些码控制可见性，而后端没有任何端点检查它们 —— "
                        + "结果是「看得见点不开」或「能用但找不到」，且都不报错。"
                        + "要么改成端点实际用的码，要么说明理由后加进 known-menu-perm-mismatch.txt")
                .isSubsetOf(known);
    }

    @Test
    @DisplayName("后端每个资源在菜单上都有入口")
    void everyBackendResourceIsReachable() throws IOException {
        Set<String> navPerms = new LinkedHashSet<>();
        for (Leaf lf : leaves()) {
            if (lf.perm() != null) navPerms.add(resource(lf.perm()));
        }
        Set<String> navModules = navModules();
        assertThat(navModules).as("应当解析到 section 的 module").isNotEmpty();

        Set<String> unreachable = new TreeSet<>();
        for (String p : backendPerms()) {
            String r = resource(p);
            if (!navPerms.contains(r) && !navModules.contains(r.split(":")[0])) unreachable.add(r);
        }
        assertThat(unreachable)
                .as("后端建了这些资源的端点，而菜单上一个入口都没有 —— 能力建了用户找不到")
                .isEmpty();
    }

    /** {@code a:b:c} → {@code a:b}；菜单叶声明 :read，页内动作是 :create，按资源比才有意义。 */
    private static String resource(String perm) {
        String[] b = perm.split(":");
        return b.length >= 2 ? b[0] + ":" + b[1] : perm;
    }

    // ───────────────────────── 解析 ─────────────────────────

    private static List<Leaf> leaves() throws IOException {
        String src = Files.readString(navTs(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        List<Leaf> out = new ArrayList<>();
        for (Matcher m = LEAF.matcher(src); m.find(); ) {
            String body = m.group(1);
            Matcher h = HREF.matcher(body), l = LABEL.matcher(body), p = PERM.matcher(body);
            if (!h.find() || !l.find()) continue;
            out.add(new Leaf(h.group(1), l.group(1), p.find() ? p.group(1) : null));
        }
        for (Matcher b = OP_BLOCK.matcher(src); b.find(); ) {
            for (Matcher r = OP_ROW.matcher(b.group(1)); r.find(); ) {
                out.add(new Leaf("/operation/" + r.group(1), r.group(2),
                        r.group(3).isEmpty() ? null : r.group(3)));
            }
        }
        return out;
    }

    private static Set<String> navModules() throws IOException {
        String src = Files.readString(navTs(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        Set<String> out = new LinkedHashSet<>();
        for (Matcher m = MODULE.matcher(src); m.find(); ) out.add(m.group(1));
        for (Matcher m = MODULES.matcher(src); m.find(); ) {
            for (Matcher q = QUOTED.matcher(m.group(1)); q.find(); ) out.add(q.group(1));
        }
        return out;
    }

    private static Set<String> routes() throws IOException {
        Path app = repoRoot().resolve("ops-web/app");
        Set<String> out = new LinkedHashSet<>();
        try (var s = Files.walk(app)) {
            s.filter(f -> f.getFileName().toString().equals("page.tsx")).forEach(f -> {
                String rel = app.relativize(f.getParent()).toString().replace('\\', '/');
                out.add(rel.isEmpty() ? "/" : "/" + rel);
            });
        }
        return out;
    }

    private static Set<String> backendPerms() throws IOException {
        JsonNode root = new ObjectMapper()
                .readTree(repoRoot().resolve("docs/api/contract.json").toFile());
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode e : root.path("endpoints")) {
            String p = e.path("perm").asText(null);
            if (p != null && !p.isBlank()) out.add(p);
        }
        return out;
    }

    private static Set<String> readLedger(Path p) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    private static Path navTs() {
        return repoRoot().resolve("ops-web/lib/nav.ts");
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("known-arch-exemptions.txt"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("找不到 backend 根");
        return p;
    }

    private static Path repoRoot() {
        return backendRoot().getParent();
    }
}
