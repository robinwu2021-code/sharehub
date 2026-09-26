package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 FinanceController 的 6 个裸 {@code Map} 请求体换成 record 之后，露出来的两条真缺陷。
 *
 * <p>裸 Map 当请求体 = 这个端点没有契约（{@code known-map-request-bodies.txt}）：
 * 契约抽不出形状，靠它的「表单字段 vs 后端写入面」卡口对这些端点全是盲的，
 * 拼错的键静默忽略。换成 record 之后两端才能被机械地比一次。
 *
 * <ol>
 *   <li><b>出账勾的对象没生效</b>：运营端出账抽屉有「选择要出账的对象」多选
 *       （帮助文案还写着「该周期没有分润明细的对象会被拒绝」），前端把 {@code payeeNos}
 *       发过来，而端点只读 period/payeeType —— <b>勾了一个，出的是该类型全部收款方的账</b>。
 *       结算单是钱，多出来的还要人去撤。</li>
 *   <li><b>买断原因静默丢弃</b>：前端发 {@code reason}，端点读 {@code note} ——
 *       而买断是没收用户押金，{@code ord_deposit.note} 是这笔钱唯一的说明。
 *       （同一个抽屉里还有个「买断金额」输入框，后端定为押金全额、根本不看它，已一并撤掉。）</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceMapBodyContractTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    final List<String> deposits = new ArrayList<>();
    final List<String> shares = new ArrayList<>();
    final List<String> settles = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String d : deposits) jdbc.update("DELETE FROM ord_deposit WHERE deposit_no=?", d);
        for (String s : shares) jdbc.update("DELETE FROM share_record WHERE record_no=?", s);
        for (String s : settles) {
            jdbc.update("DELETE FROM stl_settlement_detail WHERE settle_no=?", s);
            jdbc.update("DELETE FROM stl_settlement WHERE settle_no=?", s);
        }
    }

    @Test
    @DisplayName("★★ 出账只给勾中的收款方出 —— 此前 payeeNos 整个被忽略，出的是全部")
    void generateHonoursPayeeSelection() {
        String admin = login("ADMIN");
        String period = "2019-01";          // 远离任何真实账期，避免与种子数据纠缠
        String picked = "VEN_PICK" + rnd();
        String other = "VEN_SKIP" + rnd();
        share(period, picked, "30.00");
        share(period, other, "40.00");

        Map<String, Object> body = new HashMap<>();
        body.put("period", period);
        body.put("payeeType", "VENUE");
        body.put("payeeNos", List.of(picked));
        List<String> created = new ArrayList<>();
        post("/api/trade/settlements/generate", body, admin).okData()
                .forEach(n -> created.add(n.asText()));
        settles.addAll(created);

        assertThat(created).as("勾了一个就该出一张").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT payee_no FROM stl_settlement WHERE settle_no=?",
                String.class, created.get(0))).isEqualTo(picked);
        assertThat(jdbc.queryForObject("SELECT status FROM share_record WHERE record_no=?",
                String.class, shares.get(1)))
                .as("没勾的那个不该被这次出账消耗掉 —— 它的分润记录必须还是 PENDING")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("★★ 买断原因落库 —— 此前前端发 reason、后端读 note，原因丢了")
    void buyoutReasonLands() {
        String admin = login("ADMIN");
        String no = "DEP_BT" + rnd();
        deposits.add(no);
        jdbc.update("INSERT INTO ord_deposit (deposit_no, tenant_id, order_no, c_user_no, amount, currency, status)"
                + " VALUES (?, 'MAIN', ?, 'CU-TEST', 50.00, 'AED', 'HELD')", no, "ORD-" + no);

        post("/api/trade/deposits/" + no + "/buyout",
                Map.of("reason", "超时未归还，按买断处理"), admin).okData();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, note, buyout_amount FROM ord_deposit WHERE deposit_no=?", no);
        assertThat(row).containsEntry("status", "BOUGHT_OUT");
        assertThat(String.valueOf(row.get("note")))
                .as("买断是没收用户押金，note 是这笔钱唯一的说明 —— 丢了就解释不清")
                .contains("超时未归还");
        assertThat((BigDecimal) row.get("buyout_amount"))
                .as("金额恒为押金全额，不看传参").isEqualByComparingTo("50.00");
    }

    private void share(String period, String payeeNo, String amount) {
        String no = "SHR" + rnd();
        shares.add(no);
        jdbc.update("INSERT INTO share_record (record_no, tenant_id, order_no, dimension, payee_type, payee_no,"
                        + " amount, currency, mode, period, status)"
                        + " VALUES (?, 'MAIN', ?, 'GMV', 'VENUE', ?, ?, 'AED', 'LEDGER', ?, 'PENDING')",
                no, "ORD-" + no, payeeNo, new BigDecimal(amount), period);
    }

    private static String rnd() {
        return java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
