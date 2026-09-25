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
 * 后端往库里写一个运营端联合类型里没有的值，那一行的状态就<b>不在取值集里</b>：
 * 徽标映射不上、按它筛一条都查不到 —— 而两边都不报错，
 * 运营只会以为「没有这种单子」。
 *
 * <h2>这不是假设，一天之内撞了四个</h2>
 * 2026-09-24：
 * <ul>
 *   <li>{@code ord_deposit.status} 后端写 {@code BUYOUT}、前端只有 {@code BOUGHT_OUT}（V64 已归一）；</li>
 *   <li>{@code stl_settlement.status} 后端与 DDL 都是 {@code GEN}、前端单方面改成了 {@code DRAFT}；</li>
 *   <li>{@code dev_cabinet.status} 后端有 {@code IN_STOCK}（新建机柜的默认值、
 *       整个「库存调拨」管的就是它），前端联合类型里<b>根本没有</b>；</li>
 *   <li>另有一个同型的在数据范围下拉里（见 {@code DataScopeOptionsTest}）。</li>
 * </ul>
 *
 * <h2>自动发现，不靠人登记</h2>
 * <b>两端同名即比对</b>：扫后端所有 {@code public enum X}、扫运营端
 * {@code ops-web/lib/types} 下所有 {@code export type X = "A" | "B"}，名字相同的成对检查。
 *
 * <p>早先这里是一张手工维护的清单，只盯了 3 对 —— 而同名的其实有 14 对。
 * 手工清单的问题不是「漏了 11 对」，是<b>「新加一对时没人会记得来登记」</b>：
 * 一张需要人记得更新的清单，早晚会停在某个版本上。
 *
 * <h2>为什么比词表而不是比数据</h2>
 * 库里现在恰好没有在库的机柜、也只有一行 {@code GEN} 的结算单 —— 靠数据是查不全的。
 * 词表是「将来会写什么」，数据只是「已经写了什么」。
 *
 * <h2>发现不了的两种情况（说清楚边界）</h2>
 * <ul>
 *   <li><b>两端名字不同</b>的同一套词表 —— 比如后端 {@code XxxState} 对前端 {@code XxxStatus}。
 *       这种只能靠人看出来；</li>
 *   <li>前端<b>内联</b>在 interface 里的联合（{@code status: "A" | "B"}）——
 *       解析器只认具名 {@code export type}。碰到了就把它提成具名类型，
 *       顺手也让它进入覆盖。</li>
 * </ul>
 */
class StatusVocabularyAcrossEndsTest {

    private static final Path BACKEND_ROOT = Path.of("..");
    private static final Path FRONTEND_TYPES = Path.of("..", "..", "ops-web", "lib", "types");

    @Test
    @DisplayName("★★ 两端同名的状态词表必须一字不差——对不上的症状是「筛了什么都没有」")
    void the_two_ends_agree_on_every_shared_vocabulary() throws IOException {
        Map<String, Set<String>> backend = backendEnums();
        Map<String, Set<String>> frontend = frontendUnions();

        Set<String> shared = new TreeSet<>(backend.keySet());
        shared.retainAll(frontend.keySet());
        assertThat(shared).as("前提：两端应当有同名词表可比（一个都找不到说明解析坏了）").isNotEmpty();

        Map<String, String> mismatched = new LinkedHashMap<>();
        for (String name : shared) {
            Set<String> b = backend.get(name);
            Set<String> f = frontend.get(name);
            if (b.equals(f)) continue;
            Set<String> onlyBackend = new TreeSet<>(b);
            onlyBackend.removeAll(f);
            Set<String> onlyFrontend = new TreeSet<>(f);
            onlyFrontend.removeAll(b);
            mismatched.put(name, "后端独有=" + onlyBackend + " 前端独有=" + onlyFrontend);
        }

        assertThat(mismatched).as("""
                这些词表两端对不上。后端写进库的值若不在运营端的取值集里，
                那一行的状态徽标映射不上、按它筛一条都查不到 —— 而两边都不报错。

                **以 DDL 列注释为准**定哪边是对的（库里的存量值也是证据），改另一边；
                若要改的是已落库的值，加一条归一迁移（参考 V41 / V64）。
                「这个名字更通行」是**标签**的事不是**值**的事 —— 值对齐，标签随便挑。""")
                .isEmpty();
    }

    // ——————————————————————— 扫两端 ———————————————————————

    /** 后端所有 {@code public enum X { A, B; … }} → 名字到常量集合。 */
    private static Map<String, Set<String>> backendEnums() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        try (var files = Files.walk(BACKEND_ROOT)) {
            for (Path p : files.filter(StatusVocabularyAcrossEndsTest::isMainJava).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("public enum (\\w+)\\s*\\{").matcher(src);
                if (!m.find()) continue;
                String body = src.substring(m.end());
                int semi = body.indexOf(';');
                if (semi >= 0) body = body.substring(0, semi);
                body = body.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                // 带构造参数 / 常量体的枚举（如 FileCategory("…", Set.of(PDF, JPEG), …)）：
                // 括号与花括号里的大写标识符是参数，不是常量 —— 只留顶层的常量名
                body = topLevelOnly(body.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\""));
                Set<String> vals = new TreeSet<>();
                Matcher v = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\b").matcher(body);
                while (v.find()) vals.add(v.group(1));
                if (!vals.isEmpty()) out.put(m.group(1), vals);
            }
        }
        return out;
    }

    /** 去掉所有 ( … ) 与 { … } 里的内容（可嵌套），只留枚举常量声明的顶层。 */
    static String topLevelOnly(String body) {
        StringBuilder out = new StringBuilder(body.length());
        int depth = 0;
        for (char c : body.toCharArray()) {
            if (c == '(' || c == '{') depth++;
            else if (c == ')' || c == '}') depth = Math.max(0, depth - 1);
            else if (depth == 0) out.append(c);
        }
        return out.toString();
    }

    private static boolean isMainJava(Path p) {
        String s = p.toString();
        return s.endsWith(".java") && s.contains("/src/main/java/") && !s.contains("/target/");
    }

    /** 运营端所有 {@code export type X = "A" | "B";} → 名字到取值集合。 */
    private static Map<String, Set<String>> frontendUnions() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (!Files.exists(FRONTEND_TYPES)) return out;
        try (var files = Files.walk(FRONTEND_TYPES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".ts")).toList()) {
                Matcher m = Pattern.compile("export type (\\w+)\\s*=\\s*([^;]+);")
                        .matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    Set<String> vals = new TreeSet<>();
                    Matcher lit = Pattern.compile("\"([A-Z][A-Z0-9_]*)\"").matcher(m.group(2));
                    while (lit.find()) vals.add(lit.group(1));
                    if (!vals.isEmpty()) out.put(m.group(1), vals);
                }
            }
        }
        return out;
    }
}
