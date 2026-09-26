package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.finance.service.SettlementService;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **出账之后才产生的同账期分润，必须能进结算单**。
 *
 * <h3>这条是生产实测撞出来的</h3>
 * 2026-09-26 在 powerbank.ichain.top 上跑业务流程验证时发现：账期 {@code 2026-09} 已有两张结算单，
 * 而同账期还躺着 8 条 PENDING 分润（合计 42.00）—— 无论触发多少次出账，返回都是
 * 「账期 2026-09 没有待出账的分润（或已出过）」。钱算出来了、却永远进不了任何结算单，
 * <b>而且没有任何日志说它被跳过了</b>。
 *
 * <p>根因：防重复结算本来有两道闸，而第一道锁错了对象 ——
 * <ul>
 *   <li>真正管用的是第二道：分润出账后置 {@code DONE}，下次根本捞不到它；</li>
 *   <li>第一道「{@code (payeeNo, period)} 已有单就跳过整个收款方」挡住的不是重复的分润，
 *       是<b>出账之后才产生的该账期分润</b> —— 订单延迟结算、补录、纠错重算都会产生。</li>
 * </ul>
 *
 * <p>修法是**并入**已有单而不是开补充单：库上有唯一键 {@code (payee_type, payee_no, period)}，
 * 一个收款方一个账期只能一张单，那是财务口径，不该为了绕开它而改表。
 * 已打款的单则不能动（钱已经出去了），记 WARN 让它显形。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementLateShareTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SettlementService settlements;

    private final String period = "2091-07";           // 未来账期，不与任何现有数据相撞
    private final String payee = "VTPAYEE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM stl_settlement_detail WHERE settle_no IN (SELECT settle_no FROM stl_settlement WHERE period=?)", period);
        jdbc.update("DELETE FROM stl_settlement WHERE period=?", period);
        jdbc.update("DELETE FROM share_record WHERE period=?", period);
    }

    @Test
    @DisplayName("★ 出账后又来一条同账期分润 → 并入原单，不再静默跳过")
    void late_share_is_merged_into_the_existing_settlement() {
        share("10.00");
        List<String> first = settlements.generate(period, null);
        assertThat(first).as("第一次出账应生成一张单").hasSize(1);
        String settleNo = first.get(0);
        assertThat(total(settleNo)).isEqualByComparingTo("10.00");

        // —— 出账之后才产生的那一条（延迟结算 / 补录 / 纠错重算都会这样）——
        share("2.50");
        List<String> second = settlements.generate(period, null);

        assertThat(second).as("应当返回被并入的那张单，而不是空").containsExactly(settleNo);
        assertThat(count(settleNo)).as("明细应当是两条").isEqualTo(2);
        assertThat(total(settleNo)).as("金额按明细重算：10.00 + 2.50").isEqualByComparingTo("12.50");
        assertThat(pending()).as("并入后不该再有待出账的").isZero();
    }

    @Test
    @DisplayName("并入是幂等的：同一条分润不会被结两次")
    void merging_is_idempotent() {
        share("7.00");
        String settleNo = settlements.generate(period, null).get(0);
        share("3.00");
        settlements.generate(period, null);
        BigDecimal afterMerge = total(settleNo);

        settlements.generate(period, null);   // 再跑两次
        settlements.generate(period, null);

        assertThat(total(settleNo)).as("重跑不该把金额叠上去").isEqualByComparingTo(afterMerge);
        assertThat(count(settleNo)).as("明细数也不该增长").isEqualTo(2);
    }

    @Test
    @DisplayName("已打款的单不能动——钱已经出去了，再加明细会让账实不符（跳过但记 WARN）")
    void paid_settlement_is_left_alone() {
        share("5.00");
        String settleNo = settlements.generate(period, null).get(0);
        jdbc.update("UPDATE stl_settlement SET status='PAID' WHERE settle_no=?", settleNo);

        share("9.99");
        settlements.generate(period, null);

        assertThat(total(settleNo)).as("已打款的单金额不许变").isEqualByComparingTo("5.00");
        assertThat(count(settleNo)).isEqualTo(1);
        assertThat(pending()).as("那条分润仍留在待出账，等人工处理 —— 而不是被悄悄标成已结").isEqualTo(1);
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private void share(String amount) {
        jdbc.update("INSERT INTO share_record (record_no, tenant_id, order_no, payee_type, payee_no, payee_name, "
                        + "amount, currency, mode, status, period, dimension) "
                        + "VALUES (?, 'MAIN', ?, 'VENUE', ?, '【VT】验证受益方', ?, 'AED', 'RATE', 'PENDING', ?, 'VENUE')",
                "VTSR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "VTORD" + UUID.randomUUID().toString().substring(0, 8), payee, new BigDecimal(amount), period);
    }

    private BigDecimal total(String settleNo) {
        return jdbc.queryForObject("SELECT total_amount FROM stl_settlement WHERE settle_no=?", BigDecimal.class, settleNo);
    }

    private int count(String settleNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM stl_settlement_detail WHERE settle_no=?", Integer.class, settleNo);
        return n == null ? 0 : n;
    }

    private int pending() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM share_record WHERE period=? AND status='PENDING'", Integer.class, period);
        return n == null ? 0 : n;
    }
}
