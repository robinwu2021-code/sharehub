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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 棘轮：请求体是裸 {@code Map<…>} 的处理器只准变少。
 *
 * <p><b>裸 Map 当请求体 = 这个端点没有契约</b>。没人能从签名上说出它认哪些键，于是：
 * 契约抽取拿不到 {@code requestShape}（靠它的检查器全盲 —— ops-web 那条
 * 「表单字段 vs 后端写入面」对这批端点一个都核不了，而它上线第一天就查出四处
 * 「运营填了存不进去」，其中一处每次 500）；拼错的键**静默忽略**；前端类型只能靠人对着抄。
 *
 * <p>与 {@link EntityRequestBodyRatchetTest} 是同一件事的另一半：
 * 那条盯「实体当请求体」（字段太多），这条盯「Map 当请求体」（连字段都没有）。
 * 两条都只准变短。
 *
 * <p><b>迁移不是机械替换</b>：少数端点用 {@code in.containsKey("x")} 区分「没传」与
 * 「传了空」，record 表达不了这个区别（{@code CabinetServiceImpl.save} 就靠它做到
 * 「locationNo 缺省=不动、传空=解绑」）。所以台账头部写了「转之前先读一遍 service」，
 * 这里也不强推 —— 棘轮只保证不再长。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class MapRequestBodyRatchetTest {

    /**
     * {@code @RequestBody [(...)] [final] [java.util.]Map<…> 名字}
     *
     * <p><b>注解允许带包名</b>：本仓库有 17 个文件在用全限定 Spring 注解
     * （{@code @org.springframework.transaction.annotation.Transactional} 就在
     * {@code CabinetServiceImpl} 里）。只写 {@code @RequestBody} 的话
     * {@code @org.springframework.web.bind.annotation.RequestBody} 一个都扫不到 ——
     * 负对照正是这么撞出来的：加了一个违规处理器，卡口却是绿的。
     * 今天还没有这种写法，所以补上它不改变任何现有判定，只是把雷提前拆了。
     *
     * <p>泛型允许一层嵌套：{@code Map<String, List<String>>} 是真实存在的
     * （{@code IamAdminController#setRolePermissions}）。第一版只写 {@code <[^>]*>}，
     * 它在那里断掉，于是**少扫到一个**而看起来像「扫全了」——
     * 和 contract.json 逐个对过才发现差这一个。
     */
    private static final Pattern MAP_BODY = Pattern.compile(
            "@(?:[\\w.]*\\.)?RequestBody(?:\\s*\\([^)]*\\))?\\s+(?:final\\s+)?(?:java\\.util\\.)?"
                    + "Map\\s*<(?:[^<>]|<[^<>]*>)*>\\s+(\\w+)");

    /** 往前找最近的方法声明名，用来把违规定位到 {@code 类#方法}。 */
    private static final Pattern METHOD_DECL =
            Pattern.compile("(?:public|protected)\\s+[\\w.<>,\\[\\]\\s?]+?\\s(\\w+)\\s*\\(");

    @Test
    @DisplayName("台账外不许再有「裸 Map 作 @RequestBody」")
    void noNewMapRequestBodies() throws IOException {
        Path backend = backendRoot();

        Set<String> actual = new LinkedHashSet<>();
        for (Path f : mainJavaFiles(backend)) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (!src.contains("@RequestBody")) continue;
            String cls = f.getFileName().toString().replaceFirst("\\.java$", "");
            for (Matcher m = MAP_BODY.matcher(src); m.find(); ) {
                actual.add(cls + "#" + enclosingMethod(src, m.start()));
            }
        }
        // 扫描本身必须有效：一个都没扫到说明路径或正则坏了，而不是「没有违规」
        assertThat(actual).as("应当扫描到裸 Map 请求体（一个都没有更可能是正则坏了）").isNotEmpty();

        Set<String> known = readLedger(backend.resolve("known-map-request-bodies.txt"));
        assertThat(actual)
                .as("台账外新增的裸 Map 请求体。**先读一遍对应 service**："
                        + "若它用 containsKey 区分「没传 / 传了空」，照 record 直转是行为回退；"
                        + "否则改成 Req/Command record。确实要保留的，说明理由后加进 "
                        + "known-map-request-bodies.txt")
                .isSubsetOf(known);
    }

    @Test
    @DisplayName("台账不该比实际还长（转完 DTO 记得删条目）")
    void ledgerHasNoStaleEntries() throws IOException {
        Path backend = backendRoot();
        Set<String> known = readLedger(backend.resolve("known-map-request-bodies.txt"));
        assertThat(known).as("台账不该为空").isNotEmpty();

        Set<String> actual = new LinkedHashSet<>();
        for (Path f : mainJavaFiles(backend)) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (!src.contains("@RequestBody")) continue;
            String cls = f.getFileName().toString().replaceFirst("\\.java$", "");
            for (Matcher m = MAP_BODY.matcher(src); m.find(); ) {
                actual.add(cls + "#" + enclosingMethod(src, m.start()));
            }
        }
        Set<String> stale = new LinkedHashSet<>(known);
        stale.removeAll(actual);
        assertThat(stale)
                .as("这些已经不是裸 Map 请求体了，请从 known-map-request-bodies.txt 删掉 —— "
                        + "台账留着过期条目，下一个人就会以为这里还没迁")
                .isEmpty();
    }

    private static String enclosingMethod(String src, int at) {
        String head = src.substring(0, at);
        String last = "?";
        for (Matcher m = METHOD_DECL.matcher(head); m.find(); ) {
            last = m.group(1);
        }
        return last;
    }

    private static Set<String> readLedger(Path p) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("known-map-request-bodies.txt"))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("找不到 backend 根（含 known-map-request-bodies.txt）");
        return p;
    }

    private static List<Path> mainJavaFiles(Path backend) throws IOException {
        try (Stream<Path> s = Files.walk(backend)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .toList();
        }
    }
}
