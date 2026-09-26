package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务日期一律走 {@code BizClock#today()}，不许直调 {@code LocalDate.now()}。
 *
 * <h2>这个缺陷长什么样</h2>
 * {@code LocalDate.now()} 取的是**服务器 JVM 时区**。生产实测跑在
 * {@code Asia/Shanghai}（{@code -Duser.timezone=Asia/Shanghai}），而业务在阿联酋
 * （{@code sharehub.biz-zone=Asia/Dubai}）。于是每天有 <b>4 小时</b> ——
 * 迪拜 20:00 到午夜，正是租借高峰 —— 它比业务「今天」<b>早一天</b>。
 *
 * <p>2026-09-27 01:15 实测到的两个后果：
 * <ul>
 *   <li>有效期到 9-30 的券，迪拜 9-30 20:00 起判为过期 ——
 *       而那段代码的注释正好写着「券凭空作废，那是用户看得见、又说不出所以然的损失」；</li>
 *   <li>合同签署撞上「日期不能晚于今天」，10 条用例同时红。</li>
 * </ul>
 *
 * <h2>为什么必须用卡口，而不是「注意一下」</h2>
 * 这类缺陷**每天自己好一次**：过了那 4 小时再跑就全绿。于是它看起来像偶发抖动，
 * 而不是缺陷。最坏的情况是有人为了让它别再闪红，去<b>放宽服务端校验</b> ——
 * 那是把真约束拆掉，换来一个永远不会红的测试。
 *
 * <h2>台账而不是一刀切</h2>
 * 报表刻意用 {@code Clock.systemUTC()}（{@code ord_order.rent_start_at} 存的是 UTC
 * 字符串），那是**正当的**；分润归属期间要先定「哪个时间戳权威」才谈得上改
 * （{@code endedAt} 是服务器时区的 {@code LocalDateTime}，{@code rentEndAt} 是 UTC 字符串
 * —— 数据本身就是混的）。所以留台账，逐条写明理由，**只准变短**。
 */
class BizDateFromBizClockTest {

    private static final String LEDGER = "known-localdate-now.txt";

    /** 直调：`LocalDate.now()` 无参。带时区参数的 `LocalDate.now(zone)` 是对的，不算。 */
    private static final Pattern RAW = Pattern.compile("LocalDate\\s*\\.\\s*now\\s*\\(\\s*\\)");

    private static final Pattern ENTRY = Pattern.compile("^(\\S+)\\s+(\\d+)$");

    @Test
    @DisplayName("★ 业务日期不许直调 LocalDate.now() —— 服务器时区比业务今天早一天，每天 4 小时")
    void raw_localdate_now_matches_the_ledger() throws IOException {
        Path backend = backendRoot();
        Map<String, Integer> actual = new TreeMap<>(scan(backend));
        Map<String, Integer> ledger = new TreeMap<>(readLedger(backend.resolve(LEDGER)));

        assertThat(actual)
                .as("""
                        与 %s 不一致。改少了就把数字调小（台账只准变短）；
                        新增的直调请先判断它是不是**业务日期** —— 是就注入 BizClock 用 today()，
                        不是（比如报表按 UTC 切日）就写明「为什么这里该用服务器/UTC 时区」再进台账。
                        判据见 BizClock 的类注释。""", LEDGER)
                .isEqualTo(ledger);
    }

    // ───────────────────────── 扫描 ─────────────────────────

    private static Map<String, Integer> scan(Path backend) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Path f : mainJavaFiles(backend)) {
            String cls = f.getFileName().toString().replaceFirst("\\.java$", "");
            String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));
            int n = 0;
            for (Matcher m = RAW.matcher(src); m.find(); ) {
                n++;
            }
            if (n > 0) out.merge(cls, n, Integer::sum);
        }
        return out;
    }

    /**
     * 扫之前先剥注释 —— **否则解释这条规则的文字本身会被算成违规**。
     *
     * <p>第一版没剥，于是 {@code BizClock} 的类注释（里面有一张对比表，写着
     * {@code LocalDate.now()}）和 {@code JobInvocation} 的说明各自被记了一笔。
     * 把它们加进台账就等于承认「解释规则也算犯规」，那会让台账失去意义 ——
     * 下一个人会照着加豁免，而不是照着改代码。
     *
     * <p>字符串字面量不剥：真要有人把它拼进 SQL 或日志里，那也值得看一眼。
     */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static Map<String, Integer> readLedger(Path p) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int hash = t.indexOf('#');           // 行尾注释写的是「为什么这里不走 BizClock」
            if (hash >= 0) t = t.substring(0, hash).trim();
            if (t.isEmpty()) continue;
            Matcher m = ENTRY.matcher(t);
            assertThat(m.matches()).as("台账行格式应为 `类名 处数`，实际：%s", line).isTrue();
            out.merge(m.group(1), Integer.parseInt(m.group(2)), Integer::sum);
        }
        return out;
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
