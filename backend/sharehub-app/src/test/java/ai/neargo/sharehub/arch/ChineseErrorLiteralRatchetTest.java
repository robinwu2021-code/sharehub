package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 棘轮：错误文案里的中文字面量只准变少。
 *
 * <h2>它挡的是什么</h2>
 * {@code throw new IllegalArgumentException("中文…")} 经 {@code GlobalExceptionHandler} 的
 * {@code Messages.msg(...)} —— 而那个方法对**非 key 的成品串原样返回** ——
 * 一字不改地送到两端客户端，两端又都优先显示 {@code body.message}。
 * 于是界面切到英文/阿语之后，<b>页面是英文、报错还是中文</b>。不报错，只是读不懂。
 *
 * <p>2026-09-25 实测 410 处。先按形状收口了两批（「xxx不存在: 键」85 处、
 * 「&lt;字段名&gt; 必填」22 处），剩下的逐批翻译，本卡口盯住它别再长回来。
 *
 * <h2>为什么不按类逐个计数</h2>
 * 这个台账会被每一条并行开发的线碰到。按类计数意味着任何一次无关重构都要改它，
 * 而本仓库长期多会话并行 —— 一个天天冲突的台账，第三天就会有人直接把断言删掉。
 * 所以用「总数相等 + 类名集合只准变短」：允许已列出的类里增减，
 * <b>不许让这个模式扩散到新的类</b>。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class ChineseErrorLiteralRatchetTest {

    private static final String LEDGER = "known-chinese-error-literals.txt";

    /**
     * 三种形状都算。只认**字面量**参数；拼接表达式（`"x" + no`）的首段同样算，
     * 因为首段就是那句中文。
     *
     * <p><b>为什么第二、三种也要扫</b>：本仓库正在从 {@code IllegalArgumentException}
     * 迁往 {@code ServerException}/{@link ai.neargo.sharehub.common.BizException}（业务码 → HTTP 状态）。
     * 卡口若只盯旧形状，**迁移路径本身就是个洞** —— 实测 2026-09-25 当天
     * 新写的站点/合同/借出闸门已经在新形状上攒了 55 条硬编码中文，而卡口一声没吭。
     * 一个只覆盖旧写法的棘轮比没有更糟：它让人以为这件事有人管着。
     */
    private static final Pattern[] RAW = {
            Pattern.compile("throw new IllegalArgumentException\\(\"([^\"]*)\""),
            Pattern.compile("ServerException\\.of\\([^,)]*,\\s*\"([^\"]*)\""),
            Pattern.compile("BizException\\.(?:conflict|badRequest|of)\\(\\s*(?:[A-Za-z.]+,\\s*)?\"([^\"]*)\""),
    };

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    @Test
    @DisplayName("★★ 错误文案里的中文只准变少——它会一字不改地送到英文/阿语界面上")
    void chineseLiteralsOnlyShrink() throws IOException {
        Path backend = backendRoot();
        List<String> lines = Files.readAllLines(backend.resolve(LEDGER), StandardCharsets.UTF_8);

        int wantTotal = -1;
        Set<String> wantClasses = new TreeSet<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("TOTAL ")) {
                wantTotal = Integer.parseInt(t.substring(6).trim());
            } else {
                wantClasses.add(t);
            }
        }
        assertThat(wantTotal).as("台账里应当有一行 `TOTAL <数字>`").isGreaterThanOrEqualTo(0);

        int actualTotal = 0;
        Set<String> actualClasses = new LinkedHashSet<>();
        for (Path f : mainJavaFiles(backend)) {
            String cls = f.getFileName().toString().replaceFirst("\\.java$", "");
            String src = Files.readString(f, StandardCharsets.UTF_8);
            int n = 0;
            for (Pattern pat : RAW) {
                for (Matcher m = pat.matcher(src); m.find(); ) {
                    if (CJK.matcher(m.group(1)).find()) n++;
                }
            }
            if (n > 0) {
                actualTotal += n;
                actualClasses.add(cls);
            }
        }

        // 扫描必须有效：一条都没扫到更可能是正则或路径坏了，而不是「全清干净了」
        assertThat(actualTotal).as("应当扫到中文字面量；一条都没有更可能是扫描坏了").isPositive();

        Set<String> brandNew = new TreeSet<>(actualClasses);
        brandNew.removeAll(wantClasses);
        assertThat(brandNew).as("""
                这些类里新出现了中文错误文案 —— 它会原样送到英文/阿语界面上。
                先看形状能不能收口（见 %s 顶部的「怎么修」），不行就加 key + 三语
                （backend/sharehub-common/src/main/resources/i18n/messages*.properties）。""", LEDGER)
                .isEmpty();

        assertThat(actualTotal).as("""
                与 %s 的 TOTAL 不一致。
                  · 改少了 → 把 TOTAL 调小（台账只准变短），顺手删掉已清空的类名；
                  · 改多了 → 别加中文字面量，改用 i18n key。
                要求相等而不是「不超过」，是为了让每一次变化都在 review 里显形。""", LEDGER)
                .isEqualTo(wantTotal);
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve(LEDGER))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("找不到 backend 根（含 " + LEDGER + "）");
        return p;
    }

    private static List<Path> mainJavaFiles(Path backend) throws IOException {
        try (Stream<Path> s = Files.walk(backend)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/target/"))
                    .toList();
        }
    }
}
