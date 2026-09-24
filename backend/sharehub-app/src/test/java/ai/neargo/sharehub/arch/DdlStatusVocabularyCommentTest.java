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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每一个状态列都要把自己的词表写在列注释里。
 *
 * <h2>这一栏空着是有代价的：词表失去了裁定依据</h2>
 * 本仓库把 <b>DDL 列注释当作词表的裁定依据</b> —— 两端对不上时以它为准改另一边
 * （见 {@link StatusVocabularyAcrossEndsTest} 的失败提示，以及 V64 / V65 的迁移说明）。
 * 一列没有注释，就没有任何一处能说某个值是错的。
 *
 * <h2>不是推想：V65 修的两条就是这么来的</h2>
 * <ul>
 *   <li>{@code notify_template.status} 建表 {@code DEFAULT 'ACTIVE'}，
 *       而服务写 {@code ENABLED}、运营端只认 {@code ENABLED/DISABLED}；</li>
 *   <li>{@code ad_slot.status} 建表 {@code DEFAULT 'ACTIVE'}，
 *       而服务写 {@code IDLE}、运营端只认 {@code IDLE/OCCUPIED}。</li>
 * </ul>
 * 两列都没有注释，那个 {@code ACTIVE} 于是躺了很久。走服务建的行没事，
 * <b>所有不走服务的插入路径</b>（种子 SQL、数据导入、手工补行、将来任何漏了这一列的 INSERT）
 * 落进去的都是一个两端都不认识的值：徽标映射不上、按状态筛一条都查不到，而且不报错。
 *
 * <h2>为什么查库而不是查迁移脚本</h2>
 * 迁移是「建了什么」，库是「现在有什么」。注释可以由后来的 {@code ALTER} 补上
 * （{@code pay_channel.mode} 就是：建表时没有、后来补了），
 * 只看建表语句会把它误判成缺注释 —— 而误判会让台账越对越乱。
 * 同 {@link TableFieldOrphanTest} 的理由。
 *
 * <h2>这条卡口拦什么</h2>
 * 只拦<b>新出现</b>的无注释状态列（台账只准变短）。台账里那些是历史欠账，
 * 逐条清的办法写在 {@code known-undocumented-status-columns.txt} 里。
 */
class DdlStatusVocabularyCommentTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private static final Path LEDGER = Path.of("..", "known-undocumented-status-columns.txt");

    /** 这几个列名承载业务词表。{@code type} 也算 —— 它同样是一套封闭取值。 */
    private static final Set<String> VOCABULARY_COLUMNS = Set.of("status", "state", "type", "mode");

    @Test
    @DisplayName("★ 状态列必须在列注释里写明词表——没有注释就没有裁定依据，写错了也没人能说它错")
    void every_status_column_documents_its_vocabulary() throws IOException {
        Set<String> undocumented = new TreeSet<>(jdbc.queryForList("""
                SELECT CONCAT(table_name, '.', column_name)
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND column_name IN ('status', 'state', 'type', 'mode')
                   AND data_type = 'varchar'
                   AND (column_comment IS NULL OR column_comment = '')
                   -- Flyway 自己的账本表不归我们管
                   AND table_name NOT LIKE 'flyway%'""", String.class));
        assertThat(VOCABULARY_COLUMNS).as("前提：SQL 里的列名清单与常量一致").hasSize(4);

        Set<String> allowed = ledger();
        assertThat(allowed).as("前提：读得到台账（读不到会把所有列都报成新增）").isNotEmpty();

        Set<String> added = new TreeSet<>(undocumented);
        added.removeAll(allowed);
        assertThat(added).as("""
                这些状态列没有词表注释。本仓库拿 DDL 列注释当词表的裁定依据 ——
                没有它，任何一处写进去的值都无从证伪，包括建表时随手给的那个默认值。
                （V65 修的两条就是这么来的：DEFAULT 'ACTIVE' 而两端都不认识 ACTIVE。）

                加一条 MODIFY COLUMN 迁移把词表写进注释，参考 V65。
                ⚠️ MODIFY COLUMN 要把类型 / 可空 / 默认值原样重写一遍，漏写等于悄悄改掉它们 ——
                   先从 information_schema 抄下来，别照着建表 DDL 猜。""")
                .isEmpty();

        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(undocumented);
        assertThat(stale).as("""
                台账里这些列已经有注释了（或者列没了）。从
                known-undocumented-status-columns.txt 里删掉 ——
                留着的话台账会慢慢变成一张没人敢动的名单，那时它就不再表示
                「这些是还没还的账」。""")
                .isEmpty();
    }

    private static Set<String> ledger() throws IOException {
        if (!Files.exists(LEDGER)) return Set.of();
        return Files.readAllLines(LEDGER, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
