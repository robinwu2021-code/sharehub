package ai.neargo.sharehub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志打印规范的卡口。
 *
 * <h2>三条都是「现在是 0，锁死它」</h2>
 * 2026-09-24 实测：{@code printStackTrace} 0 处、{@code System.out} 0 处、{@code System.err} 0 处。
 * 与包循环、字段注入同一个判断 —— <b>维持很便宜，失去后恢复很贵</b>：
 * 等到散落几十处时再清，既要改代码又要逐个判断"这处是不是有理由"。
 *
 * <h2>为什么这三个特别值得禁</h2>
 * 它们**绕过 logback**，于是同时失去四样东西：
 * <ul>
 *   <li>不落盘 —— 只进 stdout，被 systemd 塞进 journald，与应用日志分家；</li>
 *   <li>不带 {@code traceId} / {@code actor} —— 排障时无法与同一次请求的其它行关联；</li>
 *   <li>不受级别控制 —— 生产想关也关不掉；</li>
 *   <li>不受异步与容量上限约束 —— 一次异常风暴能直接把磁盘写满。</li>
 * </ul>
 * <b>`e.printStackTrace()` 尤其危险</b>：它看起来"记了日志"，
 * 于是没人再去补一行 {@code log.error}，而那个栈其实谁也找不到。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class LoggingConventionTest {

    /** 注释与字符串里出现不算 —— 本仓的注释里就引用过这些名字来解释为什么禁。 */
    private static final Pattern PRINT_STACK = Pattern.compile("(?<!//.{0,200})\\.printStackTrace\\s*\\(");
    private static final Pattern SYS_OUT = Pattern.compile("System\\s*\\.\\s*(out|err)\\s*\\.\\s*print");

    @Test
    @DisplayName("主代码不得出现 printStackTrace / System.out / System.err")
    void noConsolePrintingInMainCode() throws IOException {
        List<Path> files = mainJavaFiles();

        // 扫描面必须有效：一个文件都没扫到说明路径坏了，而不是「没有违规」
        assertThat(files).as("应当扫描到主代码 java 文件").hasSizeGreaterThan(100);

        List<String> bad = new ArrayList<>();
        for (Path f : files) {
            String src = stripComments(Files.readString(f, StandardCharsets.UTF_8));
            if (PRINT_STACK.matcher(src).find()) bad.add(f.getFileName() + " → printStackTrace");
            if (SYS_OUT.matcher(src).find()) bad.add(f.getFileName() + " → System.out/err");
        }

        assertThat(bad)
                .as("这三个绕过 logback：不落盘、不带 traceId/actor、不受级别与容量约束。"
                        + "异常请用 log.error(\"... {}\", 业务键, e)")
                .isEmpty();
    }

    /** 粗剥注释即可：本测试只要避免「注释里提到这些名字」被误判。 */
    private static String stripComments(String s) {
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.replaceAll("//[^\n]*", "");
    }

    private static List<Path> mainJavaFiles() throws IOException {
        Path backend = backendRoot();
        try (Stream<Path> s = Files.walk(backend)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/target/"))
                    .toList();
        }
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("known-arch-exemptions.txt"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("找不到 backend 根");
        return p;
    }
}
