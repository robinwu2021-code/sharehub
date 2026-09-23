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
 * 棘轮：状态 / 类型的**裸字符串字面量**只准变少（L1.5 词表收敛）。
 *
 * <h2>为什么要盯</h2>
 * 业务状态的合法取值现在在三个地方各写一遍、互不相认：建表 DDL 的注释（55 套词表）、
 * 领域模型文档（12 个对象）、以及 Java 代码里的**裸字符串**（业务状态 enum 数量：0）。
 * 没有任何一处对另外两处有约束力。
 *
 * <p>代价不是"不好看"。{@code AlarmServiceImpl} 曾经查 {@code status IN ('OPEN','ACK')}，
 * 而 {@code "ACK"} 是**事件名**、落库状态是 {@code ACKED} —— 已受理的告警永远开不出工单，
 * 不报错、不留日志。差一个字母，编译器无从分辨。同类分叉还有 {@code CANCELLED}/{@code CANCELED}、
 * 文档里的 {@code RECLAIM} vs 代码与 DDL 的 {@code REVOKE}。
 * 详见 {@code docs/technical/实体-领域对象对账表.md} §三。
 *
 * <h2>为什么锚在用法上，而不是扫所有大写字符串</h2>
 * {@code ^[A-Z_]+$} 会把权限码、表名、事件名、枚举常量全扫进来，噪音淹没信号。
 * 本卡口只认状态值**真正流过的那几个口子**：{@code setXxx("L")} ·
 * {@code "L".equals(getXxx())} · {@code getXxx().equals("L")} ·
 * {@code .eq(X::getXxx, "L")} · {@code .in(X::getXxx, ...)}，
 * 字段名以 Status / Type / Level / Action / Mode 结尾。
 *
 * <p>这个锚点的说服力在于：**告警那个缺陷正是 {@code .in(DevAlarm::getStatus, List.of("OPEN","ACK"))}**
 * —— 本卡口若早存在，它在写下的当天就会红。
 *
 * <h2>为什么五类字段一次定全</h2>
 * 以后再往锚点里加类别，会让台账**变长** —— 而台账的全部意义是「只准变短」。
 * 一个能被放宽的棘轮不是棘轮。所以 Status(132) / Type(20) / Mode(5) / Action(3) / Level(1)
 * 从第一天就都在内，哪怕后四类量很小。
 *
 * <h2>收敛方向</h2>
 * 每个对象一个 {@code enum}，**值仍以 String 落库**（实体字段保持 {@code String}，
 * 不动 MyBatis 类型处理器 —— 那是连锁改动，收益不抵风险），只把字面量的**来源**收到一处。
 * 收完一个域就从台账删掉对应几行。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class BareStatusLiteralRatchetTest {

    /** 承载词表的字段名后缀 —— 见类注释「为什么五类一次定全」。 */
    private static final String FIELD = "\\w*(?:Status|Type|Level|Action|Mode)";

    /** 字面量：全大写下划线，形如 OPEN / ROLLED_BACK。 */
    private static final String LIT = "[A-Z][A-Z0-9_]*";

    /** `.setXxxStatus("OPEN")` —— 写入 */
    private static final Pattern SET = Pattern.compile("\\.set(" + FIELD + ")\\(\\s*\"(" + LIT + ")\"");

    /** `"OPEN".equals(x.getXxxStatus())` —— 字面量在前 */
    private static final Pattern EQ_LEFT =
            Pattern.compile("\"(" + LIT + ")\"\\s*\\.equals\\(\\s*[\\w.]*get(" + FIELD + ")\\(\\)");

    /** `x.getXxxStatus().equals("OPEN")` —— 字面量在后 */
    private static final Pattern EQ_RIGHT =
            Pattern.compile("get(" + FIELD + ")\\(\\)\\s*\\.equals\\(\\s*\"(" + LIT + ")\"");

    /** `.eq(X::getXxxStatus, "OPEN")` —— MyBatis-Plus 条件 */
    private static final Pattern MP_EQ =
            Pattern.compile("\\.eq\\(\\s*\\w+::get(" + FIELD + ")\\s*,\\s*\"(" + LIT + ")\"");

    /** `.in(X::getXxxStatus, List.of("OPEN","ACKED"))` —— 一次多个值，逐个取 */
    private static final Pattern MP_IN =
            Pattern.compile("\\.in\\(\\s*\\w+::get(" + FIELD + ")\\s*,([^;]{0,300})");

    private static final Pattern ANY_LIT = Pattern.compile("\"(" + LIT + ")\"");

    @Test
    @DisplayName("台账外不许再有裸状态/类型字面量")
    void noNewBareLiterals() throws IOException {
        Path backend = backendRoot();
        Set<String> actual = scan(backend);

        // 扫描本身必须有效：一条都没扫到，说明路径或正则坏了，而不是「没有违规」。
        // arch-guard 正是这么假绿过一次（B2 修的 EXTRA_SRC）—— 绿得完美，什么都没在守。
        assertThat(actual)
                .as("应当扫描到裸字面量；一条都没有更可能是扫描坏了")
                .isNotEmpty();

        Set<String> known = readLedger(backend.resolve("known-bare-status-literals.txt"));

        assertThat(actual)
                .as("台账外的裸状态/类型字面量 —— 要么改用 enum 常量，"
                        + "要么说明理由后加进 known-bare-status-literals.txt（加之前先想清楚为什么这一处特殊）")
                .isSubsetOf(known);
    }

    @Test
    @DisplayName("台账里不留已消失的条目（收敛完记得删行）")
    void ledgerHasNoStaleEntries() throws IOException {
        Path backend = backendRoot();
        Set<String> actual = scan(backend);
        Set<String> known = readLedger(backend.resolve("known-bare-status-literals.txt"));

        Set<String> stale = new TreeSet<>(known);
        stale.removeAll(actual);
        assertThat(stale)
                .as("这些条目在代码里已经不存在了，从台账删掉 —— "
                        + "留着会让台账看起来比实际长，下次没人敢信它")
                .isEmpty();
    }

    // ───────────────────────── 扫描 ─────────────────────────

    /** 条目形如 {@code AlarmServiceImpl#status=OPEN}。 */
    private static Set<String> scan(Path backend) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (Path f : mainJavaFiles(backend)) {
            String cls = f.getFileName().toString().replaceFirst("\\.java$", "");
            String src = Files.readString(f, StandardCharsets.UTF_8);

            collect(out, cls, SET, src, 1, 2);
            collect(out, cls, EQ_LEFT, src, 2, 1);
            collect(out, cls, EQ_RIGHT, src, 1, 2);
            collect(out, cls, MP_EQ, src, 1, 2);

            for (Matcher m = MP_IN.matcher(src); m.find(); ) {
                String field = m.group(1);
                for (Matcher v = ANY_LIT.matcher(m.group(2)); v.find(); ) {
                    out.add(entry(cls, field, v.group(1)));
                }
            }
        }
        return out;
    }

    private static void collect(Set<String> out, String cls, Pattern p, String src, int fieldG, int litG) {
        for (Matcher m = p.matcher(src); m.find(); ) {
            out.add(entry(cls, m.group(fieldG), m.group(litG)));
        }
    }

    /** 字段名首字母转小写：{@code Status} → {@code status}，与实体字段名一致。 */
    private static String entry(String cls, String field, String literal) {
        String f = field.isEmpty() ? field : Character.toLowerCase(field.charAt(0)) + field.substring(1);
        return cls + "#" + f + "=" + literal;
    }

    // ───────────────────────── 台账与文件 ─────────────────────────

    private static Set<String> readLedger(Path p) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out;
    }

    /** 从测试的工作目录（模块根）向上找到 backend 目录。 */
    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("known-bare-status-literals.txt"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("找不到 backend 根（含 known-bare-status-literals.txt）");
        }
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
