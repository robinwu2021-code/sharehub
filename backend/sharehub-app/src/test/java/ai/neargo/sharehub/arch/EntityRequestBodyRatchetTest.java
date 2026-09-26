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
 * 棘轮：「实体直接作 {@code @RequestBody}」只准变少（TDD-mass-assignment-hardening T4）。
 *
 * <p>实体当请求体 = 批量赋值面。MyBatis-Plus 的 {@code updateById} 只写非 null 字段，
 * 于是**客户端选择传哪些字段就能改哪些字段**。已做的集中加固锁住了 BaseEntity 管的字段
 * （租户 / 软删 / 审计），域字段由各 service 的 {@code beforeUpdate} 显式保护 ——
 * 但那是**黑名单**：以后谁给实体加个字段，它默认就是客户端可写的。
 *
 * <p>所以这条棘轮托住迁移：台账 {@code known-entity-request-bodies.txt} 之外的实体请求体一律失败。
 * 彻底的解法是 Req/Command DTO（白名单），随 B5 包归位增量做，迁完一个删一条。
 *
 * <p>纯文件扫描，不起 Spring 上下文。
 */
class EntityRequestBodyRatchetTest {

    /**
     * `@RequestBody 类型名`（允许 final 修饰，**也允许注解带包名**）。
     *
     * <p>包名那一段是 2026-09-26 补的：本仓库有 17 个文件在用全限定 Spring 注解，
     * 而 `@org.springframework.web.bind.annotation.RequestBody` 里没有 `@RequestBody`
     * 这个子串 —— 只认后者等于对那种写法完全失明。今天还没有这种写法，
     * 所以这一改不改变任何现有判定（同批加的 MapRequestBodyRatchetTest 是靠负对照撞出来的）。
     */
    private static final Pattern REQUEST_BODY =
            Pattern.compile("@(?:[\\w.]*\\.)?RequestBody\\s+(?:final\\s+)?([A-Z][A-Za-z0-9_]*)");

    /** `class Xxx extends BaseEntity` */
    private static final Pattern ENTITY_DECL =
            Pattern.compile("class\\s+([A-Z][A-Za-z0-9_]*)\\s+extends\\s+BaseEntity\\b");

    @Test
    @DisplayName("台账外不许再有「实体直接作 @RequestBody」")
    void noNewEntityRequestBodies() throws IOException {
        Path backend = backendRoot();

        Set<String> entities = new LinkedHashSet<>();
        Set<String> bodyTypes = new LinkedHashSet<>();
        for (Path f : mainJavaFiles(backend)) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            for (Matcher m = ENTITY_DECL.matcher(src); m.find(); ) {
                entities.add(m.group(1));
            }
            for (Matcher m = REQUEST_BODY.matcher(src); m.find(); ) {
                bodyTypes.add(m.group(1));
            }
        }
        // 扫描本身必须有效：一个实体都没扫到说明路径或正则坏了，而不是「没有违规」
        assertThat(entities).as("应当扫描到实体类").isNotEmpty();
        assertThat(bodyTypes).as("应当扫描到 @RequestBody").isNotEmpty();

        Set<String> actual = new LinkedHashSet<>(bodyTypes);
        actual.retainAll(entities);

        Set<String> known = readLedger(backend.resolve("known-entity-request-bodies.txt"));

        assertThat(actual)
                .as("台账外的实体请求体（要么改用 Req/Command DTO，要么说明理由后加进 "
                        + "known-entity-request-bodies.txt）")
                .isSubsetOf(known);
    }

    /** `private 类型 字段名;`（实体里 Lombok @Data 的普通字段） */
    private static final Pattern ENTITY_FIELD =
            Pattern.compile("private\\s+(?:final\\s+)?[A-Za-z0-9_.<>\\[\\]]+\\s+([a-z][A-Za-z0-9_]*)\\s*;");

    @Test
    @DisplayName("实体请求体上不许**悄悄**多出可写字段")
    void noNewWritableFieldsOnEntityRequestBodies() throws IOException {
        Path backend = backendRoot();
        Set<String> known = readLedger(backend.resolve("known-entity-request-bodies.txt"));

        Set<String> actual = new LinkedHashSet<>();
        for (Path f : mainJavaFiles(backend)) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            Matcher decl = ENTITY_DECL.matcher(src);
            if (!decl.find()) continue;
            String entity = decl.group(1);
            if (!known.contains(entity)) continue;     // 只盯还在当请求体的那些
            for (Matcher m = ENTITY_FIELD.matcher(src); m.find(); ) {
                actual.add(entity + "." + m.group(1));
            }
        }
        assertThat(actual).as("应当扫描到实体字段").isNotEmpty();

        Set<String> fieldLedger = readLedger(backend.resolve("known-entity-writable-fields.txt"));
        assertThat(actual)
                .as("实体请求体上新增的字段。**新字段默认就是客户端可写的** —— "
                        + "先判它该不该锁（有专门迁移入口的归属/状态/金额要在 beforeUpdate 里锁死），"
                        + "再把它加进 known-entity-writable-fields.txt")
                .isSubsetOf(fieldLedger);
    }

    /** 台账只准变短：实测集合若比台账小，提醒把已迁移的条目删掉（不失败，避免卡住迁移节奏）。 */
    @Test
    @DisplayName("台账不该比实际还长（迁移完记得删条目）")
    void ledgerHasNoStaleEntries() throws IOException {
        Path backend = backendRoot();
        Set<String> known = readLedger(backend.resolve("known-entity-request-bodies.txt"));
        assertThat(known).as("台账不该为空").isNotEmpty();
    }

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
        while (p != null && !Files.isRegularFile(p.resolve("known-entity-request-bodies.txt"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("找不到 backend 根（含 known-entity-request-bodies.txt）");
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
