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
 * VocabularyColumns#MAX_DISTINCT_FOR_ENUM}）且个个是大写形。名字叫什么无关。
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
 * {@link VocabularyColumns#CLASSIC_NAMES} 那五个名字仍按名字查，保证只增不减。
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
 * <h2>判据本身住在哪</h2>
 * 「哪些列算词表列」抽到了 {@link VocabularyColumns}，与写入侧台账
 * {@link VocabularyWriteCoverageTest} 共用。2026-09-28 建那份台账时分叉过一次：
 * 只抄了「注释里有没有词表」，漏了这里最要紧的「存的值像不像枚举」，
 * 于是哈希列被记成了词表列。<b>共用之后不可能再分叉</b>，
 * 唯一留给各自决定的是**空列**（这里跳过，台账保留）。
 *
 * <p>数据来自共享的累积测试库（见 {@code application-test.yml}）：
 * 某个用例写进一个词表外的值，这里就会红 —— 那正是要拦的。
 */
class StoredValueInVocabularyTest extends ApiTestSupport {

    /** 与 {@link VocabularyColumns} 同一个形状判据 —— 本类第二条用例要逐个取值判。 */
    private static final Pattern ENUM_SHAPED = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★ 库里存的状态值必须在该列注释声明的词表里——脏数据比坏代码难查")
    void every_stored_status_value_is_declared_by_its_own_column() {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        /*
         * 「哪些列算词表列」的判据在 VocabularyColumns，与写入侧台账
         * VocabularyWriteCoverageTest 共用 —— 两边分叉过一次（那次漏的是取值判据），
         * 结果是台账里记着一堆哈希列。判据只有一份就不可能再分叉。
         *
         * 这里在共用判据之上再加一步：**空列跳过**。没有数据就没有脏数据，
         * 查它等于查空气；而写入侧台账对空列的取态正相反（新表最该表态），
         * 所以那一步留给各自决定，不放进共用判据里。
         */
        for (VocabularyColumns.Column col : VocabularyColumns.scan(jdbc)) {
            if (!col.looksLikeEnum() && !col.classicName()) continue;
            if (col.hasNoValues()) continue;
            checked++;

            for (Map<String, Object> row : col.rows()) {
                String value = String.valueOf(row.get("v"));
                if (!col.vocabulary().contains(value)) {
                    offenders.add(col.key() + " = '" + value + "' × " + row.get("n")
                            + " 行（词表 " + col.vocabulary() + "）");
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

    /**
     * 已知的「混形列」豁免：<b>这两列本来就不是枚举</b>，不是脏数据。
     *
     * <ul>
     *   <li>{@code wo_sla_rule.priority} —— 词表里带一个 {@code *} 哨兵（「类型默认」），
     *       它在注释里写着，但 {@code [A-Z][A-Z0-9_]+} 提不出这种符号；</li>
     *   <li>{@code iam_dept.path} —— 祖先路径 {@code /D1/D3/}，本来就是自由文本，
     *       注释里那几个大写词说的是别的意思。</li>
     * </ul>
     *
     * <p><b>这份清单不许变长</b>：再多一个，多半不是「又一个哨兵」，而是又一列被脏值关掉了。
     */
    private static final Set<String> MIXED_SHAPE_ALLOWED =
            Set.of("wo_sla_rule.priority", "iam_dept.path");

    /**
     * ★★ <b>一个脏值不能把整列的检查悄悄关掉。</b>
     *
     * <h2>上面那条有个洞，这条堵它</h2>
     * 上面按「取值像不像枚举」判一列该不该查，而判据是<b>全部取值都得是大写形</b>。
     * 于是：<b>往一个枚举列里写一个中文值，这一列从此不再被检查</b> ——
     * 连同它原有的脏数据一起，从卡口的视野里消失。
     * <b>唯一能发现脏数据的卡口，可以被脏数据本身关掉。</b>
     *
     * <p>2026-09-28 实测到了这个形状：{@code cs_ticket.channel} 140 行里有 1 行是
     * {@code '电话'}（词表是 APP/MP/H5/PHONE/EMAIL/MANUAL/ALARM）。
     * 那一行进去之后，上面那条对这一列**静默停检**，而它显示为绿。
     *
     * <h2>为什么不干脆放宽上面的判据</h2>
     * 想过把「全部大写形」改成「大部分大写形」，量过全库只有 3 列是混形的：
     * {@code cs_ticket.channel}（1/140 行非枚举形）· {@code iam_dept.path}（2/4）·
     * {@code wo_sla_rule.priority}（4/7）。按行数比例能分开，但那是个会漂的魔法数；
     * 按「非枚举形的值有没有写在注释里」也分不开（path 的 {@code D001/D002} 不在注释里，
     * 却是正常值）。<b>放宽判据的代价是误报，而误报会让人把卡口关掉</b> ——
     * 那比漏报更糟。所以这里另立一条窄的，带一份**只准变短**的豁免清单。
     */
    @Test
    @DisplayName("★★ 枚举列里混进非枚举形的值——一个脏值就能把上面那条对整列停检")
    void a_single_dirty_value_must_not_silently_disable_the_column() {
        List<String> mixed = new ArrayList<>();
        for (VocabularyColumns.Column col : VocabularyColumns.scan(jdbc)) {
            if (col.hasNoValues() || col.looksLikeEnum()) continue;      // 空列 / 本来就全是枚举形
            if (col.rows().size() > VocabularyColumns.MAX_DISTINCT_FOR_ENUM) continue;  // 取值太多，是自由文本
            if (MIXED_SHAPE_ALLOWED.contains(col.key())) continue;

            List<String> odd = col.rows().stream()
                    .map(r -> String.valueOf(r.get("v")))
                    .filter(v -> !ENUM_SHAPED.matcher(v).matches())
                    .toList();
            long enumShaped = col.rows().size() - odd.size();
            if (enumShaped == 0) continue;   // 一个大写形取值都没有 = 这一列本来就不是枚举

            mixed.add(col.key() + " 混进了 " + odd + "（另有 " + enumShaped
                    + " 个取值是枚举形；词表 " + col.vocabulary() + "）");
        }

        assertThat(mixed).as("""
                这些列**大部分取值是枚举形，却混进了非枚举形的值**。

                要紧的不是这一个值本身，是它的副作用：
                上面那条「库里存的值必须在词表里」按「取值全是大写形」判该不该查这一列，
                **混进一个异形值，这一列就从那条卡口的视野里整个消失了** ——
                连同它原有的脏数据，而那条依旧显示为绿。

                二选一：
                  · 值是错的 → 修数据 + 补写入侧校验（词表列的写入侧台账见
                    known-unvalidated-vocab-columns.txt）；
                  · 这一列本来就不是枚举（哨兵值 / 自由文本）→ 加进 MIXED_SHAPE_ALLOWED
                    并写明理由。**那份清单只准变短。**""")
                .isEmpty();
    }
}
