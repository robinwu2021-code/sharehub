package ai.neargo.sharehub.arch;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @TableField} 改了列名，就不许库里还留着「按字段名直译出来的那一列」。
 *
 * <h2>这个 bug 有精确的签名，所以值得单独盯</h2>
 * 实体写 {@code @TableField("assignee_id") private String assigneeNo} ——
 * 字段叫 {@code assigneeNo}、列叫 {@code assignee_id}。
 * 任何**不读这个注解**的对账工具都会按字段名推出「应该有一列 assignee_no」，
 * 判定缺列，然后有人照着建。V13 就是这么给三张表各加了一列从未被写过的
 * {@code assignee_no}（已由 V63 删除）。
 *
 * <h2>为什么孤儿列不是「几个字节」的事</h2>
 * 下一个人看到 {@code wo_handle} 上同时有 {@code assignee_id} 与 {@code assignee_no}，
 * 会<b>以为两者各有含义</b>，然后花时间去找「什么时候写哪一个」——
 * 而答案是「其中一个从来没被写过」。<b>孤儿列误导的是人，不是机器。</b>
 *
 * <h2>为什么查库而不是查迁移脚本</h2>
 * 迁移是「建了什么」，库是「现在有什么」。一列可能建了又删（本例就是），
 * 只看迁移会把已经删掉的列当成还在。真正要断言的是最终状态。
 */
class TableFieldOrphanTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 主代码里所有实体源文件。
     *
     * <p><b>从 backend 根自动发现，不写模块清单</b>：清单漏一个模块，那个模块里的孤儿列
     * 就永远扫不到 —— 而卡口照样是绿的。这个方向的失误没有任何症状。
     * 同 {@code scripts/_modules.py}（Python 侧被同一类失误咬了五次之后改的就是这个）。
     */
    private static final Path BACKEND_ROOT = Path.of("..");

    private static final Pattern TABLE_NAME = Pattern.compile("@TableName\\(\"(\\w+)\"\\)");
    /** {@code @TableField("col") ... private T fieldName;} —— 只要显式改了列名的。 */
    private static final Pattern RENAMED_FIELD = Pattern.compile(
            "@TableField\\(\\s*(?:value\\s*=\\s*)?\"(\\w+)\"[^)]*\\)\\s*"
                    + "(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*"
                    + "private\\s+[\\w<>,.\\[\\] ]+\\s+(\\w+)\\s*;");

    private record Orphan(String table, String column, String entityField, String realColumn) {
        @Override
        public String toString() {
            return table + "." + column + "（字段 " + entityField + " 实际映射 " + realColumn + "）";
        }
    }

    @Test
    @DisplayName("★ @TableField 改名后，不许库里还留着按字段名直译的那一列")
    void a_renamed_column_leaves_no_orphan_behind() throws IOException {
        Set<String> found = new TreeSet<>();

        try (var files = Files.walk(BACKEND_ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/target/")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                // 一个文件里可能有多个 @TableName（IamEntities 就是），逐段切
                Matcher t = TABLE_NAME.matcher(src);
                int prev = -1;
                String prevTable = null;
                while (t.find()) {
                    if (prevTable != null) collect(src.substring(prev, t.start()), prevTable, found);
                    prevTable = t.group(1);
                    prev = t.end();
                }
                if (prevTable != null) collect(src.substring(prev), prevTable, found);
            }
        }

        assertThat(found).as("""
                实体用 @TableField 改了列名，而库里还留着「按字段名直译出来的那一列」。
                这一列**没有任何代码会写它**——它是不读 @TableField 的对账工具误判出来的。

                留着的代价是误导人：下一个人看到两列并存会以为各有含义，
                然后去找「什么时候写哪一个」，而答案是「其中一个从来没被写过」。

                处理：确认它确实全空（`SELECT COUNT(*), SUM(该列 IS NOT NULL)`）后，
                加一条 `ALTER TABLE ... DROP COLUMN IF EXISTS ...` 迁移，参考 V63。""")
                .isEmpty();
    }

    private void collect(String body, String table, Set<String> out) {
        Matcher f = RENAMED_FIELD.matcher(body);
        while (f.find()) {
            String realColumn = f.group(1);
            String naive = snake(f.group(2));
            if (naive.equals(realColumn)) continue;   // 注解写的和直译一样，谈不上改名
            if (columnExists(table, naive)) {
                out.add(new Orphan(table, naive, f.group(2), realColumn).toString());
            }
        }
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?""",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    /** {@code assigneeNo} → {@code assignee_no}，与那个出错的脚本用的是同一条规则。 */
    private static String snake(String field) {
        return field.replaceAll("([A-Z])", "_$1").toLowerCase();
    }
}
