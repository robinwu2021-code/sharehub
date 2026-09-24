package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **后端强制的每一个权限码，都必须在目录 {@code iam_permission} 里。**
 *
 * <h2>不在会怎样</h2>
 * 目录是角色勾选树（以及将来菜单挂码选择器）的数据源。
 * 一个码不在目录里，管理员**在界面上根本选不到它** ——
 * 而那个端点一直在按它拒人。表现是「这个权限没做」，
 * 而真相是「做了，但没人能授出去」。<b>不报错、不告警。</b>
 *
 * <h2>这不是假设</h2>
 * 2026-09-24 实测：后端强制 158 个码，目录只有 57 条 —— <b>118 个选不到</b>。
 * 根因是 {@code IamSeeder} 灌目录时用的是 {@code RolePerms.MAP.values()}，
 * 那是「内置角色持有的码」而不是「后端强制的码」：前者 64、后者 158。
 * 两个数一直不一样，而没有任何东西在比对它们。
 *
 * <h2>红了怎么办</h2>
 * 新加了 {@code @perm.can('新码')} 却没登记。两步：
 * <ol>
 *   <li>在 {@code backend/scripts/perm-catalog-names.tsv} 补一行（code / 中文名 / 模块 / 是否强制）；</li>
 *   <li>{@code python3 backend/scripts/gen-perm-catalog.py} 重新生成，落进一个**新的**迁移。</li>
 * </ol>
 * 别改已提交的迁移 —— 别人的库跑过它，改了 Flyway 会因校验和不符拒绝启动。
 */
class PermCatalogCoverageTest extends ApiTestSupport {

    /** 从仓库根往回找：测试的工作目录是 backend/sharehub-app。 */
    private static final Path BACKEND = Path.of("..");
    private static final Pattern PERM = Pattern.compile("@perm\\.can\\('([^']+)'\\)");

    /** 源码里所有被强制的码（跳过 target 与测试源）。 */
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

    @Test
    void every_enforced_code_is_selectable_in_the_catalog() throws IOException {
        Set<String> codes = enforced();
        assertThat(codes).as("前提：扫得到 @perm.can 的码").isNotEmpty();

        Set<String> catalog = new TreeSet<>();
        for (JsonNode p : get("/api/platform/iam/permissions", login("ADMIN")).okData()) {
            catalog.add(p.path("code").asText());
        }
        assertThat(catalog).as("前提：目录端点有数据").isNotEmpty();

        Set<String> missing = new TreeSet<>(codes);
        missing.removeAll(catalog);
        assertThat(missing).as("""
                这些码后端在强制，但不在 iam_permission 目录里 ——
                管理员在角色勾选树上**选不到它们**，而端点一直在按它们拒人。
                补 backend/scripts/perm-catalog-names.tsv 后重新生成，落进新迁移。""")
                .isEmpty();
    }

    @Test
    void the_catalog_rows_all_have_a_real_name() {
        // 没有名字的行在勾选树上长得像乱码，而没人会回来补 —— 补齐前的 12 条通配码就是这样
        List<String> nameless = new java.util.ArrayList<>();
        for (JsonNode p : get("/api/platform/iam/permissions", login("ADMIN")).okData()) {
            String name = p.path("name").asText("");
            if (name.isBlank() || name.equals(p.path("code").asText())) {
                nameless.add(p.path("code").asText());
            }
        }
        assertThat(nameless).as("目录里这些行没有中文名（name 空或等于 code）").isEmpty();
    }
}
