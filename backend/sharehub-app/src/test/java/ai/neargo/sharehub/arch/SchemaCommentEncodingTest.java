package ai.neargo.sharehub.arch;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库里的表/列注释不许是乱码。
 *
 * <h2>为什么这值得一条卡口：注释是词表的裁定依据</h2>
 * 本仓库拿 DDL 列注释当词表的裁定依据（见 {@link StoredValueInVocabularyTest}
 * 与 {@code known-undocumented-status-columns.txt}）。注释一旦是乱码，
 * 这一列就等于没有裁定依据 —— 而它看起来是有的。
 *
 * <p>实测那批里就有一条真词表：
 * {@code stl_payout_account.payee_type = 'OPERATOR 运营主体 / VENUE 场地方'}。
 *
 * <h2>更直接的代价：乱码会被抄下去</h2>
 * {@code docs/technical/db-schema-reference.md} 是从这些注释生成的。
 * <b>一份带乱码的参考文档，下一个人会照着把乱码抄进代码注释里</b> ——
 * 而且它长得不像坏了，只像「这几个字显示不出来」。
 * 已提交的那份文档里本来就有（V69 之前）。
 *
 * <h2>这是怎么发生的</h2>
 * 迁移文件本身是干净的 UTF-8，坏在**执行那一刻的连接字符集**：
 * UTF-8 字节被当成 cp1252 读了一遍再编回 UTF-8。
 * 已应用的迁移不能改（Flyway 校验和），所以 V69 用新迁移把注释重写了一遍。
 *
 * <p>判据是这个双重编码的签名：一个 Latin-1 补充区字符后面紧跟一个
 * 控制/标点区字符 —— 正常的中文注释不会出现这种相邻。
 *
 * <p>零容忍、没有台账：V69 之后全库为 0，能归零的就该归零。
 */
class SchemaCommentEncodingTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    /** UTF-8 被当 cp1252 读一遍再编回来之后，中文会落成这种相邻形态。 */
    private static final Pattern DOUBLE_ENCODED =
            Pattern.compile("[\\u00c0-\\u00ff][\\u0080-\\u00bf\\u2000-\\u203a]");

    @Test
    @DisplayName("★ 表/列注释不许是乱码——注释是词表的裁定依据，而乱码看起来只像「显示不出来」")
    void schema_comments_are_readable() {
        List<String> broken = new ArrayList<>();

        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT table_name AS n, table_comment AS c FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_comment <> ''""")) {
            if (DOUBLE_ENCODED.matcher(String.valueOf(r.get("c"))).find()) {
                broken.add("表 " + r.get("n") + " = " + r.get("c"));
            }
        }
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT CONCAT(table_name, '.', column_name) AS n, column_comment AS c
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND column_comment <> ''""")) {
            if (DOUBLE_ENCODED.matcher(String.valueOf(r.get("c"))).find()) {
                broken.add(r.get("n") + " = " + r.get("c"));
            }
        }

        assertThat(broken).as("""
                这些注释在库里是乱码（双重编码：UTF-8 被当 cp1252 读了一遍再编回来）。

                注释是本仓库判定词表的依据，乱码等于这一列没有依据——而它看起来是有的；
                db-schema-reference.md 又是从注释生成的，乱码会被下一个人抄进代码里。

                先确认迁移文件本身是不是干净的 UTF-8（V54 那次是干净的，坏在执行时的连接字符集）。
                已应用的迁移不能改（Flyway 校验和），所以加一条新迁移把注释重写一遍，参考 V69——
                ⚠️ 其中每行的列定义要从 `SHOW CREATE TABLE` 原样抄，只换 COMMENT：
                   手写类型/可空/默认值极易出错，V69 第一版就差点静默改掉两列的默认值。""")
                .isEmpty();
    }
}
