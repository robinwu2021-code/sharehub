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
 * 棘轮：裸 {@code IllegalStateException} 只准变少。
 *
 * <h2>它挡的是什么</h2>
 * {@code GlobalExceptionHandler} 没有 {@code IllegalStateException} 的映射，裸抛一律落兜底：
 * <b>HTTP 500「服务器错误」+ 一行 {@code ERROR 未处理异常} 带栈</b>。用在业务拒绝上时两头都疼：
 * 调用方拿不到真原因（「券已领完」和「数据库挂了」长一个样），
 * 而 ERROR 按 [CLAUDE.md] 的口径是「需要人介入且不介入会持续出错」——
 * 券领完不需要任何人介入，这类噪音会把真告警埋掉。
 *
 * <p>2026-09-25 把 22 处业务拒绝改成了 {@code ServerException.of(ErrorCode.CONFLICT, …)}（→ 409）。
 * 本卡口盯住剩下的 26 处别再长回来。
 *
 * <h2>为什么不是零容忍</h2>
 * 剩下的**确实该是 500**：HMAC 不可用、会话反序列化失败、跨服务调用失败 ——
 * 一刀切改成 4xx 会让它们既不触发告警也不再记录堆栈，比现在更糟。
 * 所以走台账，并要求每条写明「为什么它该是 500」。
 *
 * <h2>为什么按类计数而不是按行号</h2>
 * 行号会被同文件里任何一处编辑顶掉，台账会为了无关改动天天变红 —— 一个天天红的卡口
 * 三天内就被所有人无视。按类计数稳定；而且**精确相等**（不是「不超过」）：
 * 同一个类里删一处又加一处会互相抵消，那样棘轮就是摆设。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class BizRejectionNotRawIllegalStateTest {

    private static final String LEDGER = "known-raw-illegal-state.txt";

    private static final Pattern RAW = Pattern.compile("throw\\s+new\\s+IllegalStateException\\s*\\(");

    /** 台账行：`类名 处数`，`#` 起的整行与行尾注释都忽略。 */
    private static final Pattern ENTRY = Pattern.compile("^([A-Za-z_$][\\w$]*)\\s+(\\d+)\\s*$");

    @Test
    @DisplayName("★★ 裸 IllegalStateException 必须与台账逐类相等——业务拒绝该走 409，不该落 500")
    void rawThrowsMatchTheLedger() throws IOException {
        Path backend = backendRoot();
        Map<String, Integer> actual = scan(backend);

        // 扫描本身必须有效：一条都没扫到，更可能是路径或正则坏了，而不是「全清干净了」。
        // 真清零那天，这条断言要连同本测试一起删，而不是留着假绿。
        assertThat(actual)
                .as("应当扫到裸 IllegalStateException；一条都没有更可能是扫描坏了")
                .isNotEmpty();

        Map<String, Integer> known = readLedger(backend.resolve(LEDGER));

        assertThat(new TreeMap<>(actual))
                .as("与 %s 不一致。改少了就把数字调小（台账只准变短）；"
                        + "新增的裸抛请先判断它是不是**业务拒绝** —— 是就改成 "
                        + "ServerException.of(ErrorCode.CONFLICT, …)，"
                        + "不是就写明「为什么它该是 500」再进台账。"
                        + "判据与逐处清单见 docs/technical/待办-业务拒绝的状态码.md", LEDGER)
                .isEqualTo(new TreeMap<>(known));
    }

    // ───────────────────────── 扫描 ─────────────────────────

    private static Map<String, Integer> scan(Path backend) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Path f : mainJavaFiles(backend)) {
            String cls = f.getFileName().toString().replaceFirst("\\.java$", "");
            String src = Files.readString(f, StandardCharsets.UTF_8);
            int n = 0;
            for (Matcher m = RAW.matcher(src); m.find(); ) {
                n++;
            }
            if (n > 0) out.merge(cls, n, Integer::sum);
        }
        return out;
    }

    private static Map<String, Integer> readLedger(Path p) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int hash = t.indexOf('#');           // 行尾注释写的是「为什么该是 500」
            if (hash >= 0) t = t.substring(0, hash).trim();
            if (t.isEmpty()) continue;
            Matcher m = ENTRY.matcher(t);
            assertThat(m.matches()).as("台账行格式应为 `类名 处数`，实际：%s", line).isTrue();
            out.merge(m.group(1), Integer.parseInt(m.group(2)), Integer::sum);
        }
        return out;
    }

    /** 从测试的工作目录（模块根）向上找到 backend 目录。 */
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
