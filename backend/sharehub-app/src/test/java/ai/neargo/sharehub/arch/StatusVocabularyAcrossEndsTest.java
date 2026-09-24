package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一套状态词表，两端必须一字不差。
 *
 * <h2>对不上的症状是「筛了什么都没有」，不是报错</h2>
 * 后端往库里写 {@code BUYOUT}，运营端的联合类型里只有 {@code BOUGHT_OUT} ——
 * 于是买断之后那一行的状态<b>不在运营端的取值集里</b>：
 * 按「买断」筛一条都查不到，状态徽标也映射不上。
 * 而两边都不报错，运营只会以为「没有买断的单子」。
 *
 * <p>这不是假设，是 2026-09-24 实际查出来的（{@code ord_deposit.status}，V64 已归一）。
 * 同一天还查出另一个同型的：运营端的数据范围下拉里有一个后端一张表都没登记的档位
 * （见 {@code DataScopeOptionsTest}）。<b>一天两个，所以值得有这道卡口。</b>
 *
 * <h2>为什么比对枚举而不是比对数据</h2>
 * 库里现在恰好没有买断的单子 —— 靠数据是查不出来的。
 * 词表是「将来会写什么」，数据只是「已经写了什么」。
 *
 * <h2>怎么加一对</h2>
 * 在 {@link #PAIRS} 里加一行：后端枚举的类名 → 运营端那个联合类型的名字。
 * 两边都要能被解析到（后端 {@code enum X { A, B }}，前端
 * {@code export type X = "A" | "B"}），解析不到会当作前提失败而不是悄悄跳过。
 */
class StatusVocabularyAcrossEndsTest {

    /** 后端枚举源文件（相对 sharehub-app） → 运营端类型文件里的联合类型名。 */
    private static final Map<String, String> PAIRS = new LinkedHashMap<>(Map.of(
            "../sharehub-svc-core/src/main/java/ai/neargo/sharehub/trade/order/DepositStatus.java",
            "DepositStatus"));

    private static final Path FRONTEND_TYPES = Path.of("..", "..", "ops-web", "lib", "types");

    @Test
    @DisplayName("★★ 后端枚举与运营端联合类型必须一字不差——对不上的症状是「筛了什么都没有」")
    void the_two_ends_agree_on_every_status_value() throws IOException {
        for (Map.Entry<String, String> pair : PAIRS.entrySet()) {
            Set<String> backend = backendEnumValues(Path.of(pair.getKey()));
            Set<String> frontend = frontendUnionValues(pair.getValue());

            assertThat(backend).as("前提：解析得到后端枚举 %s", pair.getKey()).isNotEmpty();
            assertThat(frontend).as("前提：在 ops-web/lib/types 里找得到联合类型 %s", pair.getValue())
                    .isNotEmpty();

            assertThat(backend).as("""
                    「%s」两端的取值对不上。后端写进库的值若不在运营端的取值集里，
                    表现是那一行的状态筛不出来、徽标映射不上 —— 而两边都不报错。

                    先定哪一边是对的（通常以 DDL 列注释为准），改另一边，
                    并对存量加一条归一迁移（参考 V41 / V64）。""", pair.getValue())
                    .isEqualTo(frontend);
        }
    }

    // ——————————————————————— 解析两端 ———————————————————————

    /** {@code enum X { A, B, C; …}} —— 取第一个分号之前的那串常量名。 */
    private static Set<String> backendEnumValues(Path enumFile) throws IOException {
        if (!Files.exists(enumFile)) return Set.of();
        String src = Files.readString(enumFile, StandardCharsets.UTF_8);
        int brace = src.indexOf('{', src.indexOf("enum "));
        if (brace < 0) return Set.of();
        int semi = src.indexOf(';', brace);
        String body = semi < 0 ? src.substring(brace) : src.substring(brace, semi);
        // 去掉注释，免得把 javadoc 里出现的大写词当成常量
        body = body.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\b").matcher(body);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** {@code export type X = "A" | "B";} —— 在 ops-web/lib/types 下逐文件找。 */
    private static Set<String> frontendUnionValues(String typeName) throws IOException {
        if (!Files.exists(FRONTEND_TYPES)) return Set.of();
        Pattern decl = Pattern.compile(
                "export type " + Pattern.quote(typeName) + "\\s*=\\s*([^;]+);");
        try (var files = Files.walk(FRONTEND_TYPES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".ts")).toList()) {
                Matcher m = decl.matcher(Files.readString(p, StandardCharsets.UTF_8));
                if (!m.find()) continue;
                Set<String> out = new TreeSet<>();
                Matcher lit = Pattern.compile("\"([A-Z][A-Z0-9_]*)\"").matcher(m.group(1));
                while (lit.find()) out.add(lit.group(1));
                return out;
            }
        }
        return Set.of();
    }
}
