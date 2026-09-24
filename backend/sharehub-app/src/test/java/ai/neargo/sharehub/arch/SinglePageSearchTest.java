package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试里不许「取一页结果，然后在里面找某条特定记录」。
 *
 * <h2>这一条是被同一个 bug 咬了六次之后加的</h2>
 * 测试库是<b>累积</b>的（见 {@code application.properties}）。
 * {@code get("...?page=1&size=200")} 再在返回的 list 里找刚建的那条 ——
 * 表小的时候一直对，越过页大小的那天忽然开始失败。
 *
 * <p>而失败的样子是<b>「刚建的东西查不到」</b>：看起来像功能坏了，
 * 实际是分页没够着。2026-09-24 一天之内为此查错方向六次：
 * 分润对账、代理商档案、合同列表、场地方、站点、申请单 ——
 * 每一次都先去翻业务代码，因为报出来的症状指向那里。
 *
 * <h2>「加个 keyword 过滤」不算解决</h2>
 * 各列表的 keyword 匹配哪些列<b>各不相同</b>：代理商/提现/申请单匹配业务号，
 * 而场地方只匹配名称、站点只匹配名称与区域、合同只匹配场地方名与站点名。
 * 按编号传进去一条都匹配不上 —— 而「过滤后是空的」与「确实没有」长得一模一样。
 * 本轮就这么被骗过两次。
 *
 * <p>正确写法：{@code ApiTestSupport.findInPages(path, 字段, 值, token)}，
 * 需要整体聚合时用 {@code pageAll(path, token)}。keyword 想留就留在 path 里，
 * 翻页与过滤不冲突。
 *
 * <h2>这条卡口拦什么、不拦什么</h2>
 * 只拦「单页 + 按值查找」这个组合。<b>不拦</b>只读 {@code total}、
 * 取任意一条（{@code list.get(0)}）、或对返回的行做字段形状检查 ——
 * 那些写法与表有多大无关。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class SinglePageSearchTest {

    private static final Path TEST_SRC = Path.of("src/test/java");
    private static final Path LEDGER = Path.of("..", "known-single-page-search.txt");

    /** 写死页号的单页取用 —— 正确的翻页写的是 {@code "page=" + page}。 */
    private static final Pattern HARDCODED_PAGE = Pattern.compile("page=\\d+&size=\\d+");
    private static final Pattern LIST_NODE = Pattern.compile("\\.path\\(\"list\"\\)");
    /** 「拿某个值去比对列表里某行的某个字段」。 */
    private static final Pattern SEARCH_BY_VALUE = Pattern.compile(
            "\\b\\w+\\.equals\\(\\s*\\w+\\.path\\(\"[^\"]+\"\\)\\.asText\\(\\)\\s*\\)"
                    + "|\\.path\\(\"[^\"]+\"\\)\\.asText\\(\\)\\.equals\\(");
    /** 方法头：注解（可多行）+ 签名 + 左花括号。 */
    private static final Pattern METHOD =
            Pattern.compile("\\n    (?:@[\\w.]+(?:\\([^)]*\\))?\\s*\\n    )*"
                    + "(?:private|public|protected|static|final|\\w)[^\\n;{]*\\{");

    @Test
    @DisplayName("★ 测试不许「取一页再在里面找某条记录」——用 findInPages / pageAll")
    void no_test_searches_for_a_record_inside_a_single_page() throws IOException {
        Set<String> offenders = new TreeSet<>(scan());
        Set<String> allowed = ledger();

        Set<String> added = new TreeSet<>(offenders);
        added.removeAll(allowed);
        assertThat(added).as("""
                这些方法取了一页结果，然后在里面找某条特定记录。表长到超过页大小的那天，
                它会报「刚建的东西查不到」——看起来像功能坏了，实际是分页没够着。

                改法：ApiTestSupport.findInPages(path, 字段, 值, token)；
                需要整体聚合用 pageAll(path, token)。keyword 留在 path 里即可，两者不冲突。

                确有理由保持单页的（比如就是在测「第一页返回什么」），
                加进 known-single-page-search.txt 并写明理由 —— 那会出现在 review 里。""")
                .isEmpty();

        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(offenders);
        assertThat(stale).as("""
                台账里这些条目已经不成立了（方法改好了或删掉了）。
                从 known-single-page-search.txt 里删掉 —— 留着的话台账会慢慢变成
                一张没人敢动的名单，那时它就不再表示「这些是经过判断的例外」。""")
                .isEmpty();
    }

    // ——————————————————————— 扫描 ———————————————————————

    private static List<String> scan() throws IOException {
        List<String> out = new ArrayList<>();
        try (var files = Files.walk(TEST_SRC)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                String cls = p.getFileName().toString().replace(".java", "");
                Matcher m = METHOD.matcher(src);
                while (m.find()) {
                    String body = bodyOf(src, m.end());
                    if (HARDCODED_PAGE.matcher(body).find()
                            && LIST_NODE.matcher(body).find()
                            && SEARCH_BY_VALUE.matcher(body).find()) {
                        out.add(cls + "#" + nameOf(m.group()));
                    }
                }
            }
        }
        return out;
    }

    /** 从左花括号起按配对数到方法结束。 */
    private static String bodyOf(String src, int from) {
        int depth = 1;
        int i = from;
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return src.substring(from, i);
    }

    private static String nameOf(String header) {
        Matcher n = Pattern.compile("(\\w+)\\s*\\(").matcher(header);
        String last = "?";
        while (n.find()) last = n.group(1);   // 取签名里最后一个「标识符(」= 方法名
        return last;
    }

    private static Set<String> ledger() throws IOException {
        if (!Files.exists(LEDGER)) return Set.of();
        return Files.readAllLines(LEDGER, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }
}
