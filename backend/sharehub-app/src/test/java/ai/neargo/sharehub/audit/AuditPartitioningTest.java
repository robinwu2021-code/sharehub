package ai.neargo.sharehub.audit;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计表的分区不变量（T3-5）。
 *
 * <h2>只守两条，但这两条错了都是静默的</h2>
 * <ol>
 *   <li><b>按 created_at 分区</b> —— 没分区的话，将来要清理几百万行只能 DELETE，
 *       那是长锁；而 DROP PARTITION 是瞬间的。</li>
 *   <li><b>必须有 MAXVALUE 兜底分区</b> —— 这条是重点。没有它，
 *       等最后一个分区的边界过去之后，<b>所有审计插入都会报错</b>。
 *       而审计写失败是降级不是 500（见 {@code AuditTrailInterceptor}），
 *       业务照常跑，<b>没有任何人会察觉</b>，直到某天要查审计时发现断了几个月。</li>
 * </ol>
 *
 * <p>第二条特别容易被无意破坏：有人为了归档 {@code DROP PARTITION pmax}，
 * 或者手工 REORGANIZE 时把 MAXVALUE 那截写丢了。两种都不会当场报错。
 */
class AuditPartitioningTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("审计表按 created_at 分区")
    void the_audit_table_is_partitioned_by_created_at() {
        List<String> expr = jdbc.queryForList("""
                SELECT DISTINCT partition_expression FROM information_schema.partitions
                 WHERE table_schema = DATABASE() AND table_name = 'iam_audit_log'
                   AND partition_name IS NOT NULL""", String.class);

        assertThat(expr).as("没有分区 —— 将来清理旧审计只能 DELETE，那是长锁").isNotEmpty();
        assertThat(expr.get(0)).as("分区列必须是 created_at（按月归档靠它）").contains("created_at");
    }

    @Test
    @DisplayName("★★ 必须有 MAXVALUE 兜底分区——没有它，某天起审计会静默断流")
    void there_is_always_a_catch_all_partition() {
        List<String> descriptions = jdbc.queryForList("""
                SELECT partition_description FROM information_schema.partitions
                 WHERE table_schema = DATABASE() AND table_name = 'iam_audit_log'
                   AND partition_name IS NOT NULL
                 ORDER BY partition_ordinal_position""", String.class);

        assertThat(descriptions.get(descriptions.size() - 1))
                .as("""
                        最后一个分区必须是 MAXVALUE。没有它，等最后的边界过去之后
                        审计插入会全部失败 —— 而那是降级不是 500，业务照常跑，
                        没有任何人会察觉，直到要查审计时发现断了几个月。
                        归档请 DROP 具体月份的分区，**不要 DROP pmax**。""")
                .isEqualTo("MAXVALUE");
    }
}
