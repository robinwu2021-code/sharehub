package ai.neargo.sharehub.arch;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库里存着的每一个状态值，都必须在这一列自己声明的词表里。
 *
 * <h2>这一条不需要任何映射，所以它覆盖得最全</h2>
 * 别的词表卡口都要先知道「哪个枚举对哪个类型」：
 * {@link StatusVocabularyAcrossEndsTest} 靠两端同名，
 * {@code DataScopeOptionsTest} 靠手写的两处路径。<b>本条只跟列自己比</b> ——
 * 注释是这一列声明的取值，数据是这一列实际存的值，对不上就是错。
 * 于是它一口气覆盖 80 多列，不用维护任何对应关系。
 *
 * <h2>⚠️ 范围是一份**写死的列名清单**，不是「所有列」</h2>
 * 下面那句 {@code column_name IN (...)} 才是真实范围。上一段说「不用维护任何
 * 对应关系」——而那份清单正是一份要维护的对应关系。说法与实际不符，
 * 于是没人想到去看它漏了谁。
 *
 * <p>漏掉的代价已经发生过一次：{@code dev_alarm.level} 与
 * {@code dev_alarm_code.level} 100% 存着 LOW/MEDIUM/HIGH/URGENT
 * （**工单优先级**的词表），而列注释声明的是 INFO/WARN/CRITICAL。
 * 这两列叫 {@code level}，不在当时那四个名字里，**一次都没被扫到**，
 * 本条却一直是绿的 —— 看起来像"全库都核对过了"。V82 归一，并把 level 收进清单。
 *
 * <p>范围外还有 100+ 列在注释里声明了词表（{@code channel} / {@code source} /
 * {@code direction} / {@code action} / {@code category} …），**目前不在覆盖内**。
 * 要扩清单就得同时准备好修它炸出来的存量数据 —— 只加名字不修数据，
 * 会逼下一个人把名字删回去。
 *
 * <h2>抓到过什么</h2>
 * {@code share_rule.mode} 有 9 行、{@code share_record.mode} 有 8 行是 {@code RATE}，
 * 而词表是 {@code CHANNEL_SPLIT/LEDGER}（V66 已归一）。
 * 写的人把两个概念当成了一个：{@code mode} 是<b>结算路径</b>（钱怎么走），
 * 而「按比例还是按固定额」是 {@code rate} 那一列的事。
 *
 * <p>当时没炸，是因为 {@code ShareMode.of()}（对非法值抛 400）<b>主代码里没人调</b>。
 * 哪天读侧开始解析这一列，炸的是这 17 行历史数据而不是当时写的代码 ——
 * <b>排障会从错的地方开始查</b>。这正是「脏数据比坏代码难查」的那种情况。
 *
 * <h2>零容忍，没有台账</h2>
 * 立这条卡口时全库只有上面那一处违例，修掉即可归零 ——
 * 所以这里不设豁免清单。台账是给「一次修不完」的欠账用的，
 * 能归零的就该归零：有豁免清单的卡口，下一个人会先想「能不能加进去」。
 *
 * <h2>判据的两个边界（说清楚，免得有人以为它什么都管）</h2>
 * <ul>
 *   <li><b>读不出词表的列跳过</b>：注释里凑不出两个大写取值的（比如只写了一句中文说明），
 *       本条不管 —— 那属于「没有词表」，由 {@code DdlStatusVocabularyCommentTest} 那条管；</li>
 *   <li><b>只会漏报不会误报</b>：词表按「注释里的大写词」提取，
 *       注释里多一个无关大写缩写只会让词表变大、更宽松。<b>宁可漏，不可冤</b> ——
 *       误报会让人把卡口关掉，而漏报只是没抓到。</li>
 * </ul>
 *
 * <p>数据来自共享的累积测试库（见 {@code application-test.yml}）：
 * 某个用例写进一个词表外的值，这里就会红 —— 那正是要拦的。
 */
class StoredValueInVocabularyTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    /** 注释里的取值：连续大写（含下划线数字）且至少两个字符。 */
    private static final Pattern VOCAB_TOKEN = Pattern.compile("\\b[A-Z][A-Z0-9_]+\\b");

    /** 少于两个取值就当作「这条注释没在声明词表」，跳过。 */
    private static final int MIN_VOCABULARY_SIZE = 2;

    @Test
    @DisplayName("★★ 库里存的状态值必须在该列注释声明的词表里——脏数据比坏代码难查")
    void every_stored_status_value_is_declared_by_its_own_column() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT table_name AS t, column_name AS c, column_comment AS cmt
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND column_name IN ('status', 'state', 'type', 'mode', 'level')
                   AND data_type = 'varchar'
                   AND column_comment <> ''
                   AND table_name NOT LIKE 'flyway%'""");
        assertThat(columns).as("前提：读得到带词表注释的列（读不到会让本条恒绿）")
                .hasSizeGreaterThan(50);

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Map<String, Object> col : columns) {
            String table = String.valueOf(col.get("t"));
            String column = String.valueOf(col.get("c"));
            Set<String> vocabulary = vocabularyOf(String.valueOf(col.get("cmt")));
            if (vocabulary.size() < MIN_VOCABULARY_SIZE) continue;   // 没在声明词表，见类注释
            checked++;

            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT `" + column + "` AS v, COUNT(*) AS n FROM `" + table + "`"
                            + " WHERE `" + column + "` IS NOT NULL AND `" + column + "` <> ''"
                            + " GROUP BY `" + column + "`")) {
                String value = String.valueOf(row.get("v"));
                if (!vocabulary.contains(value)) {
                    offenders.add(table + "." + column + " = '" + value + "' × " + row.get("n")
                            + " 行（词表 " + vocabulary + "）");
                }
            }
        }
        assertThat(checked).as("前提：真的核对了足够多的列").isGreaterThan(50);

        assertThat(offenders).as("""
                库里存着这一列自己都没声明的值。这样的行在运营端**映射不出状态**、
                按状态筛一条都查不到，而两边都不报错。

                先判哪边错（以列注释为准，它是本仓库的裁定依据），再二选一：
                  · 值写错了 → 改写入处，并加一条归一迁移（参考 V64 / V65 / V66）；
                  · 词表漏了这一档 → 把它补进列注释，并同步两端的取值集。

                注意排查写入方**包括种子与演示数据** —— V66 那次就是演示种子写进去的，
                而演示数据长得跟真数据一模一样。""")
                .isEmpty();
    }

    private static Set<String> vocabularyOf(String comment) {
        Set<String> out = new TreeSet<>();
        Matcher m = VOCAB_TOKEN.matcher(comment);
        while (m.find()) out.add(m.group());
        return out;
    }
}
