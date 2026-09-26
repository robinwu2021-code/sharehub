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
 *
 * <h2>范围判据是「取值的形状」，不是列的名字</h2>
 * <b>一列进不进覆盖，看它装的是不是枚举</b>：取值种类不多（≤ {@code
 * MAX_DISTINCT_FOR_ENUM}）且个个是大写形。名字叫什么无关。
 *
 * <p>为什么不能只看注释就查：{@code agt_principal.email_hash} 的注释写着
 * 「HMAC-SHA256」，按注释它也「声明了词表」，于是三千个哈希值全成了违例。
 * 编号、哈希、路径、时区都会这样撞上注释里的大写词 —— 自由文本列必须先排掉。
 *
 * <h2>为什么换掉原来那份列名清单</h2>
 * 本条原先只查 {@code column_name IN ('status','state','type','mode')}，
 * 而类注释写的是「不用维护任何对应关系」—— 那份清单正是一份要维护的对应关系，
 * <b>说法与实际不符，于是没人想到去看它漏了谁</b>。
 *
 * <p>漏掉的代价已经发生过：{@code dev_alarm.level} 与 {@code dev_alarm_code.level}
 * 100% 存着 LOW/MEDIUM/HIGH/URGENT（那是**工单优先级**的词表），
 * 而列注释声明的是 INFO/WARN/CRITICAL。这两列叫 {@code level}，不在那四个名字里，
 * <b>一次都没被扫到</b>，本条却一直绿着 —— 看起来像"全库都核对过了"。
 * 运营端 {@code /alarms} 因此整页白屏，等级筛选选哪一档都筛不出东西（V82 归一）。
 *
 * <p>实测（2026-09-25，全库 222 个声明了词表的列）：换成形状判据后，
 * 覆盖从 <b>92 列升到 149 列</b>，<b>误报归零</b>：唯二的两个
 * （{@code pay_order.order_no} / {@code share_record.agent_no}）都以 {@code _no} 结尾，
 * 而 {@code xxxNo} 是本仓库约定的业务编号，按约定不可能是枚举 —— 于是按这条
 * 结构性规则排掉，而不是去改注释迁就卡口。
 * {@link #CLASSIC_NAMES} 那五个名字仍按名字查，保证只增不减。
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

    /** 枚举形的取值：全大写 + 下划线/数字。用来判断「这一列装的是枚举还是自由文本」。 */
    private static final Pattern ENUM_SHAPED = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** 取值种类多于这个数，就不当它是枚举列（编号、哈希、路径都会撞上词表里的大写词）。 */
    private static final int MAX_DISTINCT_FOR_ENUM = 25;

    /**
     * 历来就查的四个名字 + level。保留它们是为了**只增不减**：
     * 空表在下面的「取值像枚举」判定里没有取值可判，会被跳过，
     * 而按名字它们照查不误 —— 换判据不该让任何一列掉出覆盖。
     */
    private static final Set<String> CLASSIC_NAMES =
            Set.of("status", "state", "type", "mode", "level");

    @Test
    @DisplayName("★★ 库里存的状态值必须在该列注释声明的词表里——脏数据比坏代码难查")
    void every_stored_status_value_is_declared_by_its_own_column() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT table_name AS t, column_name AS c, column_comment AS cmt
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND data_type = 'varchar'
                   AND column_comment <> ''
                   AND table_name NOT LIKE 'flyway%'""");
        assertThat(columns).as("前提：读得到带注释的 varchar 列（读不到会让本条恒绿）")
                .hasSizeGreaterThan(150);

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Map<String, Object> col : columns) {
            String table = String.valueOf(col.get("t"));
            String column = String.valueOf(col.get("c"));
            Set<String> vocabulary = vocabularyOf(String.valueOf(col.get("cmt")));
            if (vocabulary.size() < MIN_VOCABULARY_SIZE) continue;   // 没在声明词表，见类注释

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT `" + column + "` AS v, COUNT(*) AS n FROM `" + table + "`"
                            + " WHERE `" + column + "` IS NOT NULL AND `" + column + "` <> ''"
                            + " GROUP BY `" + column + "`");

            /*
             * 判据是**取值的形状**，不是列的名字。
             *
             * 为什么不能只看注释：`agt_principal.email_hash` 的注释写着「HMAC-SHA256」，
             * 按注释它「声明了词表 {HMAC, SHA256}」，于是 3000 个哈希值全成了违例。
             * 自由文本列（编号/哈希/路径/时区）都会这样撞上注释里的大写词。
             *
             * 所以再问一句：这一列装的**是不是枚举**——取值种类不多，且个个是大写形。
             * 实测（2026-09-25，全库 222 个声明了词表的列）：这条判据让覆盖从 92 列
             * 升到 151 列，而误报只剩 2 个（pay_order.order_no / share_record.agent_no
             * 是业务编号，恰好也是大写形）。那两个的注释该改成不含裸大写词。
             */
            boolean looksLikeEnum = !rows.isEmpty()
                    && rows.size() <= MAX_DISTINCT_FOR_ENUM
                    && rows.stream().allMatch(r -> ENUM_SHAPED.matcher(String.valueOf(r.get("v"))).matches());
            /*
             * `xxx_no` 是本仓库约定的**业务编号**（CLAUDE.md「契约…/ xxxNo /」），
             * 按约定不可能是枚举列。排掉它是结构性规则，不是为了迁就卡口去改注释：
             * `pay_order.order_no` 的注释提的是**别的列**的取值（RECHARGE/MEMBERSHIP），
             * `share_record.agent_no` 同理，而编号本身恰好也是大写形，于是被误伤。
             * 实测全库覆盖 151 列里只有这两个 `_no`，排掉不丢任何真枚举列。
             */
            /*
             * `xxx_ref` 同理，而且更整齐：全库 14 个 `_ref` 列**一个都不是状态列**
             * （cred_ref / disposition_ref / holder_ref / source_ref / pay_ref …
             * 装的全是别的单据的业务键）。其中三个的注释提的是**配对那一列**的词表：
             * `inv_transfer.from_ref` 写着「配合 from_type 解释（WAREHOUSE/SITE/LOCATION）」，
             * `price_rule_deprecated_v1.match_ref` 与 `price_plan_scope.scope_ref` 也是 ——
             * 于是里面装的 `WH0001` 这种业务键被判成「不在词表里」。
             * 2026-09-26 实测撞到 from_ref 那一个；一并排掉另两个将来会撞的。
             * **代价量过**：覆盖 213 → 212 列，少的那一列正是 `inv_transfer.from_ref`
             * （另外 13 个 `_ref` 列本来就没被计入 —— 表是空的或值不是大写形）。
             * 也就是说排掉它不丢任何真枚举列，只少了一个本来就在误报的。
             */
            boolean businessNumber = column.endsWith("_no") || column.endsWith("_ref");
            if (businessNumber) continue;
            if (!CLASSIC_NAMES.contains(column) && !looksLikeEnum) continue;
            checked++;

            for (Map<String, Object> row : rows) {
                String value = String.valueOf(row.get("v"));
                if (!vocabulary.contains(value)) {
                    offenders.add(table + "." + column + " = '" + value + "' × " + row.get("n")
                            + " 行（词表 " + vocabulary + "）");
                }
            }
        }
        assertThat(checked).as("前提：真的核对了足够多的列").isGreaterThan(90);

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
