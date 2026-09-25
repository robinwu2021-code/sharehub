package ai.neargo.sharehub.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每一个 {@code NOT NULL} 且**没有默认值**的列，都必须被它那张表的实体映射。
 *
 * <h2>不满足会怎样：那张表的「新建」永远 500</h2>
 * MyBatis-Plus 按实体字段拼 {@code INSERT}，实体不映射的列就不在列表里；
 * 列又是 {@code NOT NULL} 无默认 —— 数据库直接拒：
 * {@code Field 'campaign_no' doesn't have a default value}。
 *
 * <p>2026-09-25 实测：{@code POST /api/user/ad-campaigns} 必然 500。
 * 全库扫出 **10 个这样的列 / 8 张表**（广告五张 · 库存调拨 · 通知模板 · OTA 版本），
 * 而这 8 张表在测试库里**全是 0 行** —— 没有一条是建出来的。
 *
 * <h2>为什么以前没被发现</h2>
 * 读侧一切正常：列表与详情读的是种子数据，种子是 SQL 直接灌的，不走实体。
 * 坏的只有「新建」这一条路，而没人建过。
 * 成因是一批改名做了一半：V13 补了新业务键列（{@code ad_no}/{@code slot_no}/…），
 * 实体改用新列，**旧列没退役**。V83 把它们改成可空。
 *
 * <h2>为什么查库而不是查迁移脚本</h2>
 * 迁移是一串增量，某一列现在什么样要把全部 {@code ALTER} 叠起来才知道 ——
 * 本卡口第一版就是扫 {@code CREATE TABLE} 的，把 {@code wo_dispatch.assignee_id}
 * 误报成孤儿（它其实映射着）。**库是唯一说得准的那一份。**
 *
 * <h2>零容忍，没有台账</h2>
 * V83 之后全库为 0，修掉即归零。一个从第一天就是 0 的检查不需要台账。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("test")
class OrphanNotNullColumnTest {

    @Autowired
    private DataSource dataSource;

    /** 实体字段 → 列名：驼峰转下划线；{@code @TableField("x")} 显式命名优先。 */
    private static final Pattern TABLE_NAME = Pattern.compile("@TableName\\(\\s*\"(\\w+)\"");
    private static final Pattern TABLE_FIELD = Pattern.compile("@TableField\\(\\s*(?:value\\s*=\\s*)?\"(\\w+)\"");
    private static final Pattern FIELD = Pattern.compile("private\\s+[\\w<>.\\[\\]]+\\s+(\\w+)\\s*;");

    @Test
    @DisplayName("★★ NOT NULL 无默认的列必须被实体映射——否则那张表的新建永远 500")
    void every_mandatory_column_is_mapped_by_its_entity() throws IOException, SQLException {
        Map<String, Set<String>> mapped = entityColumns();
        assertThat(mapped).as("应当扫描到实体；一个都没有说明扫描坏了").isNotEmpty();

        Map<String, Set<String>> orphans = new TreeMap<>();
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement("""
                     SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL
                       AND EXTRA NOT LIKE '%auto_increment%'
                     """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString(1);
                String column = rs.getString(2);
                Set<String> cols = mapped.get(table);
                // 没有实体的表（纯种子表 / flyway 自己的表）不在本卡口范围：
                // 它们根本不走 MyBatis-Plus 的实体拼装。
                if (cols == null || cols.contains(column)) continue;
                orphans.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(column);
            }
        }

        assertThat(orphans).as("""
                这些列是 NOT NULL 且没有默认值，而它们的实体不映射 ——
                走实体 insert 时列不在 INSERT 列表里，数据库会拒：
                「Field 'xxx' doesn't have a default value」。
                **症状是那张表的「新建」永远 500，而读侧一切正常。**

                两种修法，按这一列还要不要用来选：
                  · 已退役的旧列（改名做了一半）→ 迁移里改成 NULL，注释标 retired（见 V83）；
                  · 仍是业务必填 → 给实体补上这个字段，并在 service 里校验后再落库
                    （现在是缺了就 500，而它本该是 400）。""")
                .isEmpty();
    }

    private static Map<String, Set<String>> entityColumns() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(backendRoot())) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/target/")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher t = TABLE_NAME.matcher(src);
                if (!t.find()) continue;
                Set<String> cols = out.computeIfAbsent(t.group(1), k -> new LinkedHashSet<>());
                for (Matcher m = TABLE_FIELD.matcher(src); m.find(); ) cols.add(m.group(1));
                for (Matcher m = FIELD.matcher(src); m.find(); ) cols.add(snake(m.group(1)));
                // BaseEntity 的公共列由基类声明，子类文件里看不到
                cols.addAll(Set.of("id", "tenant_id", "deleted", "created_at", "created_by",
                        "updated_at", "updated_by", "version"));
            }
        }
        return out;
    }

    private static String snake(String camel) {
        return camel.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase();
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("known-bare-status-literals.txt"))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("找不到 backend 根目录");
        return p;
    }
}
