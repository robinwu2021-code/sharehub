package ai.neargo.sharehub.arch;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「哪些列算词表列」的<b>唯一判据</b>，给两条卡口共用。
 *
 * <h2>为什么抽出来</h2>
 * {@link StoredValueInVocabularyTest}（事后：库里存的值对不对）与
 * {@link VocabularyWriteCoverageTest}（事前：写入侧接没接校验）必须对着**同一批列**说话，
 * 否则台账里的「待核实 N 列」和事后卡口查的那批列是两回事 —— 数字对不上，
 * 而那个数字是台账唯一的价值。
 *
 * <p>建 {@code VocabularyWriteCoverageTest} 时我就分叉过一次：只抄了「注释里有没有词表」
 * 这半条判据，漏了「存的值像不像枚举」那半条，于是 {@code agt_principal.phone_hash}
 * （注释写着 HMAC-SHA256）这类列被当成词表列记进了台账。
 *
 * <h2>判据（三步，顺序有意义）</h2>
 * <ol>
 *   <li><b>注释声明了词表</b>：注释里凑得出 ≥{@value #MIN_VOCABULARY_SIZE} 个大写取值，
 *       或列名是 {@link #CLASSIC_NAMES} 之一；</li>
 *   <li><b>结构性排除</b>（见 {@link #structurallyExcluded}）—— 按本仓库约定不可能是枚举的列名形状；</li>
 *   <li><b>取值形状</b>：取值种类 ≤{@value #MAX_DISTINCT_FOR_ENUM} 且个个是大写形。</li>
 * </ol>
 *
 * <h2>唯一的分叉：空列</h2>
 * 一列还没有任何数据时第 3 步<b>无从判断</b>，两条卡口对它的取态天然相反：
 * <ul>
 *   <li>事后卡口<b>跳过</b>它 —— 没有数据就没有脏数据，查它等于查空气；</li>
 *   <li>写入侧台账<b>保留</b>它 —— <b>新建的空表恰恰是最该当场表态的</b>，
 *       跳过它就等于说「新表的状态列不用接校验」，而那正是 2026-09-27
 *       一天串错三次的形状。</li>
 * </ul>
 * 所以本类只回答「这一列有没有取值、取值像不像枚举」，<b>空列怎么算由调用方自己决定</b>。
 */
final class VocabularyColumns {

    /** 注释里的取值：连续大写（含下划线数字）且至少两个字符。 */
    private static final Pattern VOCAB_TOKEN = Pattern.compile("\\b[A-Z][A-Z0-9_]+\\b");

    /** 枚举形的取值：全大写 + 下划线/数字。 */
    private static final Pattern ENUM_SHAPED = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** 少于两个取值就当作「这条注释没在声明词表」。 */
    static final int MIN_VOCABULARY_SIZE = 2;

    /** 取值种类多于这个数就不当它是枚举列（编号、哈希、路径都会撞上词表里的大写词）。 */
    static final int MAX_DISTINCT_FOR_ENUM = 25;

    /** 历来就按名字查的五个，保证换判据只增不减（空表按取值判不出来，按名字照查）。 */
    static final Set<String> CLASSIC_NAMES = Set.of("status", "state", "type", "mode", "level");

    private VocabularyColumns() {
    }

    /** 一个声明了词表、且没被结构性排除的列，连同它库里的取值分布。 */
    record Column(String table, String name, Set<String> vocabulary, List<Map<String, Object>> rows) {

        String key() {
            return table + "." + name;
        }

        /** 库里一行都没有（空表，或这一列全是 NULL/空串）—— 取值判据对它无话可说。 */
        boolean hasNoValues() {
            return rows.isEmpty();
        }

        /** 装的是枚举：取值种类不多，且个个是大写形。 */
        boolean looksLikeEnum() {
            return !rows.isEmpty()
                    && rows.size() <= MAX_DISTINCT_FOR_ENUM
                    && rows.stream().allMatch(r -> ENUM_SHAPED.matcher(String.valueOf(r.get("v"))).matches());
        }

        /** 按名字就认的那五个（见 {@link #CLASSIC_NAMES}）。 */
        boolean classicName() {
            return CLASSIC_NAMES.contains(name);
        }
    }

    /**
     * 按列名形状排掉<b>按本仓库约定不可能是枚举</b>的列。
     * 这是结构性规则，不是为了迁就卡口去改注释 —— 这些列的注释里那些大写词，
     * 说的往往是<b>配对那一列</b>的取值。
     *
     * <ul>
     *   <li>{@code xxx_no}：业务编号（CLAUDE.md「契约…/ xxxNo /」）。
     *       {@code pay_order.order_no} 的注释提的是别的列的 RECHARGE/MEMBERSHIP；</li>
     *   <li>{@code xxx_ref}：指向别的单据的业务键。全库 14 个 {@code _ref} 一个都不是状态列，
     *       {@code inv_transfer.from_ref} 的注释写的是 {@code from_type} 的词表；</li>
     *   <li>{@code xxx_hash}：HMAC 摘要。注释里写着算法名（HMAC-SHA256）就凑够了两个大写词，
     *       {@code agt_principal.phone_hash} 因此被误判过；</li>
     *   <li>{@code tenant_id}：ADR-026 定的历史遗留常量，恒 {@code MAIN}。
     *       它形状上像枚举（一个大写取值），但没有写入侧可言 —— 没人能传它。</li>
     * </ul>
     */
    static boolean structurallyExcluded(String column) {
        return column.endsWith("_no") || column.endsWith("_ref")
                || column.endsWith("_hash") || column.equals("tenant_id");
    }

    /**
     * 扫全库，返回「注释声明了词表、且没被结构性排除」的列，取值已一并读出。
     * <b>不做取值判据</b> —— 空列怎么算由调用方决定，见类注释。
     */
    static List<Column> scan(JdbcTemplate jdbc) {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT table_name AS t, column_name AS c, column_comment AS cmt
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND data_type = 'varchar'
                   AND column_comment <> ''
                   AND table_name NOT LIKE 'flyway%'""");
        if (columns.size() < 150) {
            throw new IllegalStateException(
                    "只读到 " + columns.size() + " 个带注释的 varchar 列 —— 读不到会让两条卡口都恒绿");
        }
        List<Column> out = new ArrayList<>();
        for (Map<String, Object> col : columns) {
            String table = String.valueOf(col.get("t"));
            String column = String.valueOf(col.get("c"));
            Set<String> vocabulary = vocabularyOf(String.valueOf(col.get("cmt")));
            /*
             * 注释里凑不出两个取值 = 这一列**没有词表**，两条卡口都不管它 ——
             * 那是 DdlStatusVocabularyCommentTest 的事（「该写词表却没写」）。
             * 这里对 CLASSIC_NAMES 也不网开一面：全库有 3 个
             * （share_record.status / usr_membership.level / usr_wallet_txn.type）
             * 注释是「既有DDL同名列×72」这种占位文字，放进来只会让两条卡口
             * 拿着一份空词表去判 —— 事后卡口会把每一行都报成违例。
             */
            if (vocabulary.size() < MIN_VOCABULARY_SIZE) continue;
            if (structurallyExcluded(column)) continue;

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT `" + column + "` AS v, COUNT(*) AS n FROM `" + table + "`"
                            + " WHERE `" + column + "` IS NOT NULL AND `" + column + "` <> ''"
                            + " GROUP BY `" + column + "`");
            out.add(new Column(table, column, vocabulary, rows));
        }
        return out;
    }

    /** 注释里的大写词 = 这一列声明的取值。 */
    static Set<String> vocabularyOf(String comment) {
        Set<String> out = new TreeSet<>();
        Matcher m = VOCAB_TOKEN.matcher(comment);
        while (m.find()) out.add(m.group());
        return out;
    }
}
