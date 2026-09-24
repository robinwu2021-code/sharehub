package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营端能选的每一个数据范围档位，后端都必须真的实现了。
 *
 * <h2>选一个没实现的档位，后果是「什么都看不见」而不是报错</h2>
 * {@code DataScopeHandler} 是 <b>fail-closed</b> 的：当前主体的维度在某张表的锚点里
 * 找不到对应列时，它生成的是 {@code 1=0}（全部拒绝）而不是放行
 * （见 {@code DataScopeRegistration} 类注释）。
 *
 * <p>所以运营给某个角色选了一个「后端一张表都没登记」的档位之后，
 * 那个角色下的人<b>每一页都是空的，而且没有任何报错</b> ——
 * 他们会以为是没数据，运营会以为是配好了。
 *
 * <h2>这不是假设</h2>
 * 前端的选项里长期有一个 {@code LOCATION}，而它在注册表里<b>一张表都没登记</b>；
 * 更糟的是那个档位的选择器给的是「站点」，存下去却是 {@code LOCATION} ——
 * 从一开始就没有任何一行数据能匹配上。真正实现的是 {@code SITE}
 * （登记在 loc_site / loc_location / dev_cabinet / ord_order / wo_order 五张表），
 * 而它<b>根本不在选项里</b>。两个方向同时错，而两个方向都不报错。
 *
 * <h2>为什么不拿后端的 SCOPE_TYPES 当判据</h2>
 * {@code DataScopeServiceImpl.SCOPE_TYPES} 是「**接受**哪些值」，
 * 注册表才是「**实现**了哪些维度」。前者是后者的超集
 * （它还收 LOCATION/VENUE），拿它当判据等于把这个 bug 判成合规。
 */
class DataScopeOptionsTest {

    private static final Path FRONTEND_TYPES =
            Path.of("..", "..", "ops-web", "lib", "types", "org.ts");
    private static final Path REGISTRATION =
            Path.of("src", "main", "java", "ai", "neargo", "sharehub", "config",
                    "DataScopeRegistration.java");

    /**
     * {@code ALL} 的语义是「不加任何过滤」，不需要任何表登记它 ——
     * 它是唯一一个「没登记也正确」的档位。
     */
    private static final String NO_FILTER = "ALL";

    private static final Pattern FRONT_UNION =
            Pattern.compile("export type DataScope\\s*=\\s*([^;]+);");
    private static final Pattern REGISTERED_DIMENSION =
            Pattern.compile("\"(\\w+)\"\\s*,\\s*\"\\w+\"");

    @Test
    @DisplayName("★★ 前端能选的数据范围，后端注册表里必须至少有一张表登记——否则选了它的人什么都看不见")
    void every_option_the_console_offers_is_actually_implemented() throws IOException {
        Set<String> offered = frontendOptions();
        assertThat(offered).as("前提：读得到前端的 DataScope 定义").isNotEmpty();

        Set<String> implemented = registeredDimensions();
        assertThat(implemented).as("前提：读得到后端的数据范围注册").isNotEmpty();

        Set<String> blind = new TreeSet<>(offered);
        blind.remove(NO_FILTER);
        blind.removeAll(implemented);

        assertThat(blind).as("""
                运营端把这些档位放进了下拉，而后端的数据范围注册表里**一张表都没登记它们**。
                handler 是 fail-closed —— 选中之后每一页都是空的，且没有任何报错：
                用的人以为没数据，配的人以为配好了。

                二选一：
                  · 这个档位确实该有 → 在 DataScopeRegistration 里给相关表登记锚点列；
                  · 它本来就不该有 → 从 ops-web/lib/types/org.ts 的 DataScope 里删掉。
                别用后端的 SCOPE_TYPES 来辩护 —— 那是「接受哪些值」，不是「实现了哪些维度」。""")
                .isEmpty();
    }

    // ——————————————————————— 读两边 ———————————————————————

    private static Set<String> frontendOptions() throws IOException {
        if (!Files.exists(FRONTEND_TYPES)) return Set.of();
        Matcher m = FRONT_UNION.matcher(Files.readString(FRONTEND_TYPES, StandardCharsets.UTF_8));
        if (!m.find()) return Set.of();
        Set<String> out = new TreeSet<>();
        Matcher lit = Pattern.compile("\"(\\w+)\"").matcher(m.group(1));
        while (lit.find()) out.add(lit.group(1));
        return out;
    }

    /** {@code registry.register("表", Map.of("维度", "列", …))} 里出现过的维度。 */
    private static Set<String> registeredDimensions() throws IOException {
        String src = Files.readString(REGISTRATION, StandardCharsets.UTF_8);
        Set<String> out = new TreeSet<>();
        Matcher reg = Pattern.compile("registry\\.register\\(\"\\w+\",\\s*Map\\.of\\((.*?)\\)\\);",
                Pattern.DOTALL).matcher(src);
        while (reg.find()) {
            Matcher d = REGISTERED_DIMENSION.matcher(reg.group(1));
            while (d.find()) out.add(d.group(1));
        }
        return out;
    }
}
