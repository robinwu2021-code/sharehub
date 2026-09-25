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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机的**状态**必须是枚举，不能是裸字符串。
 *
 * <h2>这一类为什么从既有棘轮的缝里漏过去了</h2>
 * {@code BareStatusLiteralRatchetTest} 锚在**用法**上 —— 字段名以
 * {@code Status}/{@code Type}/… 结尾的 setter、equals、MyBatis 条件。
 * 而状态机里的写法是：
 *
 * <pre>{@code  "CONFIRM", Map.of("GEN", "CONFIRMED")}</pre>
 *
 * <b>这里没有任何字段名</b>，五个锚点一个都不命中。于是 8 个状态机里有 6 个
 * （Powerbank / InvTransfer / Ord / Campaign / Settlement / Withdrawal）
 * 一直是裸串，而它们对应的枚举<b>早就存在</b>，只是没人用。
 *
 * <p>状态机是最不该漏的那一处：它就是「这个对象的合法状态有哪些、能怎么走」的定义处。
 * 定义处用裸串，等于这套词表根本没有唯一来源。
 *
 * <h2>为什么卡声明类型，而不是扫大写字符串</h2>
 * 状态机里**事件名与状态名会重名**：{@code PowerbankStateMachine} 的 {@code "SCRAP"}
 * 既是事件（报废动作）又是状态（已报废）。扫字面量必然要在这两者间做判断，
 * 而判断依据恰恰是"它在哪个位置"—— 那还不如直接卡位置。
 *
 * <p>迁移表的声明类型把位置说得一清二楚：
 * {@code Map<String, Map<XxxStatus, XxxStatus>>} —— <b>键是事件（动词，String），
 * 值的键与值是状态（名词，枚举）</b>。只要声明里出现
 * {@code Map<String, Map<String, String>>}，状态就还是裸串。
 * 零误报、零漏报、不需要台账。
 *
 * <h2>零容忍，没有台账</h2>
 * 立这条卡口时把 6 个都改完了，基线即 0。一个从第一天就是 0 的检查不需要台账 ——
 * 台账是给"改不动的历史"留的口子，不是给新代码留的。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class StateMachineUsesEnumTest {

    /** 裸串迁移表：{@code Map<String, Map<String, String>>}（允许任意空白）。 */
    private static final Pattern BARE_TRANSITIONS =
            Pattern.compile("Map\\s*<\\s*String\\s*,\\s*Map\\s*<\\s*String\\s*,\\s*String\\s*>\\s*>");

    /** 引用了某个状态枚举：{@code XxxStatus.CONSTANT}。 */
    private static final Pattern USES_STATUS_ENUM =
            Pattern.compile("\\b[A-Z]\\w*Status\\s*\\.\\s*[A-Z][A-Z0-9_]*\\b");

    @Test
    @DisplayName("★ 状态机的迁移表不能声明成 Map<String, Map<String, String>>——状态要是枚举")
    void transitions_are_typed_with_a_status_enum() throws IOException {
        List<Path> machines = stateMachines();

        // 扫描本身必须有效：一个状态机都没找到，说明路径坏了，而不是「全都合规」。
        assertThat(machines)
                .as("应当扫描到状态机文件；一个都没有更可能是扫描坏了")
                .isNotEmpty();

        Map<String, String> bad = new LinkedHashMap<>();
        for (Path f : machines) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            String name = f.getFileName().toString();
            if (BARE_TRANSITIONS.matcher(src).find()) {
                bad.put(name, "迁移表仍声明为 Map<String, Map<String, String>>——状态是裸串");
            } else if (!USES_STATUS_ENUM.matcher(src).find()) {
                bad.put(name, "没有引用任何 *Status 枚举常量——状态可能仍是裸串");
            }
        }

        assertThat(bad).as("""
                这些状态机的**状态**还是裸字符串。改法（不牵动 MyBatis 类型处理器）：

                  private static final Map<String, Map<XxxStatus, XxxStatus>> TRANSITIONS = Map.of(
                          "CONFIRM", Map.of(XxxStatus.GEN, XxxStatus.CONFIRMED));

                出入参保持 String（`next(String from, String event)`），
                入口处 `XxxStatus.of(from)` 解析、出口处 `.name()` 落库 ——
                只把**字面量的来源**收敛到一处，实体字段不动。

                对应的枚举多半已经存在（现有 34 个），先找一遍再新建；
                新建时词表以 **DDL 列注释**为准，并确认与运营端同名联合类型一字不差
                （StatusVocabularyAcrossEndsTest 会比）。""")
                .isEmpty();
    }

    /** 全仓 {@code *StateMachine.java}（主源码，不含测试）。 */
    private static List<Path> stateMachines() throws IOException {
        Path backend = backendRoot();
        try (Stream<Path> s = Files.walk(backend)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("StateMachine.java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .sorted()
                    .toList();
        }
    }

    /** 从测试的工作目录（模块根）向上找到 backend 目录。 */
    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("known-bare-status-literals.txt"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("找不到 backend 根目录（以 known-bare-status-literals.txt 为锚）");
        }
        return p;
    }
}
