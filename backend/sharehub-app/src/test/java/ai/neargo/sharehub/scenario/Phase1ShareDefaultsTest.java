package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.finance.service.SettlementService;
import ai.neargo.sharehub.finance.service.ShareGenerator;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家一期的默认口径（V155；需求方案 D2 / D9 / D10，优化方案 B1）。
 *
 * <p>守三件事，**都是参数驱动**，单测覆盖不到参数读取 —— 所以在真库上跑：
 * <ol>
 *   <li>分成基数默认税前，且每笔快照口径、税率、基数；改口径不追溯；</li>
 *   <li>场地方不生成分润、不出结算单 —— 即使站点挂着场地方且有生效合同；</li>
 *   <li>商家自助注册的三个公开端点一律 404。</li>
 * </ol>
 *
 * <p>站点用 ST305（挂场地方 VEN300、有生效合同 CT405）—— 正是「开关没生效就会分给场地方」的那种站点。
 * 分成方用每次新造的编号，规则只属于本类，不改别的用例的期望。
 */
class Phase1ShareDefaultsTest extends ApiTestSupport {

    private static final String SITE = "ST305";
    private static final String CABINET = "CAB1005";

    @Autowired
    private ShareGenerator generator;
    @Autowired
    private SettlementService settlements;
    @Autowired
    private JdbcTemplate jdbc;

    /** 每次运行新造分成方：规则按分成方取，复用固定编号会被前一次运行留下的规则干扰。 */
    private final String agent = "AGP1" + Long.toString(System.nanoTime(), 36).toUpperCase();

    @AfterEach
    void restoreDefaults() {
        param("finance.share.base_mode", "PRE_TAX");
        param("finance.share.venue_enabled", "0");
    }

    private void param(String key, String value) {
        jdbc.update("UPDATE sys_param SET value = ? WHERE tenant_id = 'MAIN' AND param_key = ?", value, key);
    }

    private void ruleAt30Percent() {
        post("/api/trade/share-rules", Map.of("dimension", "AGENT", "payeeNo", agent,
                "payeeName", "一期口径测试 " + agent, "mode", "LEDGER", "rate", 0.30, "priority", 1),
                login("ADMIN")).okData();
    }

    private String settle(String tag, String gross, String period) {
        String no = "ORDP1" + tag + System.nanoTime();
        generator.generate(new OrderSettledEvent(no, CABINET, SITE, agent, new BigDecimal(gross), "AED", period));
        return no;
    }

    private List<Map<String, Object>> sharesOf(String orderNo) {
        return jdbc.queryForList("SELECT payee_type, base_mode, tax_rate, base_amount, gross_amount, rate, amount "
                + "FROM share_record WHERE order_no = ? AND deleted = 0", orderNo);
    }

    private static BigDecimal dec(Object v) {
        return new BigDecimal(String.valueOf(v));
    }

    @Test
    @DisplayName("★★ 实收 10.50、VAT 5%、比例 30% → 税前基数 10.00、分成 3.00，且快照口径与税率（AC-05）")
    void share_is_computed_on_the_pre_tax_base_and_snapshotted() {
        ruleAt30Percent();
        String no = settle("T", "10.50", bizToday().toString().substring(0, 7));

        List<Map<String, Object>> rows = sharesOf(no);
        assertThat(rows).as("只应有商家一条 —— 场地方一期不分成").hasSize(1);
        Map<String, Object> r = rows.get(0);
        assertThat(r.get("payee_type")).isEqualTo("AGENT");
        assertThat(r.get("base_mode")).as("口径要快照，否则改参数后说不清这笔是怎么算的").isEqualTo("PRE_TAX");
        assertThat(dec(r.get("tax_rate"))).isEqualByComparingTo("5.00");
        assertThat(dec(r.get("gross_amount"))).as("实收原样保留").isEqualByComparingTo("10.50");
        assertThat(dec(r.get("base_amount"))).isEqualByComparingTo("10.00");
        assertThat(dec(r.get("amount"))).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("★ 站点挂着场地方且有生效合同，开关关闭时也不生成场地方分润（AC-06）")
    void venue_gets_no_share_while_the_switch_is_off() {
        ruleAt30Percent();
        String no = settle("V", "100.00", bizToday().toString().substring(0, 7));
        assertThat(sharesOf(no)).extracting(m -> m.get("payee_type")).containsExactly("AGENT");
    }

    @Test
    @DisplayName("★ 口径改为含税只影响之后的订单，历史分成不重算（AC-07）")
    void switching_the_base_mode_does_not_rewrite_history() {
        ruleAt30Percent();
        String period = bizToday().toString().substring(0, 7);
        String before = settle("B", "10.50", period);

        param("finance.share.base_mode", "GROSS");
        String after = settle("A", "10.50", period);

        assertThat(dec(sharesOf(before).get(0).get("amount"))).as("改口径前的单不动").isEqualByComparingTo("3.00");
        Map<String, Object> g = sharesOf(after).get(0);
        assertThat(g.get("base_mode")).isEqualTo("GROSS");
        assertThat(dec(g.get("tax_rate"))).as("没有折算就记 0，免得看明细的人以为扣过税").isEqualByComparingTo("0");
        assertThat(dec(g.get("base_amount"))).isEqualByComparingTo("10.50");
        assertThat(dec(g.get("amount"))).isEqualByComparingTo("3.15");
    }

    @Test
    @DisplayName("★★ 出账不生成场地方结算单，也不动场地方的分润（S11 P0：场地方仍进结算单）")
    void settlement_generation_skips_venues() {
        ruleAt30Percent();
        // 用一个没人用的账期，出账只会碰到本用例的数据
        String period = "2031-" + String.format("%02d", 1 + (int) (System.nanoTime() % 12));
        String no = settle("S", "21.00", period);
        // 模拟存量：开关关闭前生成的场地方分润
        jdbc.update("INSERT INTO share_record (record_no, tenant_id, order_no, dimension, basis, payee_type, payee_no, "
                + "amount, rate, currency, mode, status, period, gross_amount) "
                + "VALUES (?, 'MAIN', ?, 'VENUE', '', 'VENUE', 'VEN300', 2.00, 0.10, 'AED', 'LEDGER', 'PENDING', ?, 21.00)",
                "SRP1" + System.nanoTime(), no, period);

        // 与定时出账同一个调用：类型与收款方都不限
        List<String> created = settlements.generate(period, null, null);

        assertThat(jdbc.queryForList("SELECT payee_type FROM stl_settlement WHERE period = ? AND deleted = 0", String.class, period))
                .as("只应出商家的单").containsOnly("AGENT").isNotEmpty();
        assertThat(created).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM share_record WHERE order_no = ? AND payee_type = 'VENUE'",
                String.class, no)).as("场地方存量分润留在原地，不被出账消耗").isEqualTo("PENDING");
        assertThat(settlements.generate(period, "VENUE", null)).as("显式按场地方出账也不出单").isEmpty();
    }

    @Test
    @DisplayName("★ 自助注册关闭：发码、提交、查进度三个公开端点一律 404（不是 403）")
    void self_service_endpoints_are_hidden() {
        String phone = "+97150" + (1000000 + (int) (System.nanoTime() % 8999999));
        assertThat(post("/api/agent/apply/otp", Map.of("phone", phone), null).status).isEqualTo(404);
        assertThat(post("/api/agent/apply", Map.of("phone", phone, "otp", "000000", "email", "x@example.com",
                "operatorName", "关闭期间", "operatorType", "AGENT"), null).status).isEqualTo(404);
        assertThat(get("/api/agent/apply/mine?phone=" + phone + "&otp=000000", null).status).isEqualTo(404);
    }
}
