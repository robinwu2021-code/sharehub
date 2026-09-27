package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import ai.neargo.sharehub.trade.pay.StubPaymentPort;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-BL-04 欠费补缴：**先收钱再分账**（2026-09-27 拍板，见 TDD-欠费补缴与结算请款.md）。
 *
 * <p>此前结算从不请款：{@code returnOrderAt} 算完钱就 SETTLE 并发结算事件，finance 按「应收」生成分润 ——
 * 那笔钱从没收过。于是「欠费」在数据上不成立（每一张已结算单都没有支付记录），
 * 「欠费拦下次借出」无从谈起，补缴接口可以对同一张单重复收钱。
 *
 * <p>本类钉住：结算即请款；收到了才分润；没收到记欠费、拦下次借出；补缴之后分润补上、可以再借；
 * 已收的单不能再收；App 买断同样请款并分润（此前不发事件，那笔收入不分润）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerArrearsTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RentOrderService rents;
    @Autowired
    StubPaymentPort stub;

    final List<String> cabinets = new ArrayList<>();

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
    }

    @Test
    @DisplayName("请款成功：订单已收、有支付记录、有分润")
    void settlementCollectsThenShares() {
        String cab = liveCabinet();
        String token = consumerToken();
        String orderNo = rentAndReturn(token, cab);

        assertThat(payStatus(orderNo)).as("结算即请款，桩通道恒成功 → 已收").isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE order_no=? AND status='PAID'",
                Integer.class, orderNo)).as("有一笔真实的请款记录").isEqualTo(1);
        assertThat(shares(orderNo)).as("收到钱了才分润").isPositive();
    }

    @Test
    @DisplayName("请款失败：记欠费、不分润、拦下次借出；补缴后分润补上、可以再借")
    void declinedChargeBecomesArrearsUntilRepaid() {
        String cab = liveCabinet();
        String token = consumerToken();
        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        stub.declineNextFor(orderNo);
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));

        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, orderNo))
                .as("柜子已经还了、钱已经算了 —— 扣款失败不能让订单回到进行中").isEqualTo("SETTLED");
        assertThat(payStatus(orderNo)).as("请款失败 → 欠费").isEqualTo("UNPAID");
        assertThat(shares(orderNo)).as("没收到的钱不分 —— 此前按应收分，等于平台垫钱给场地方分成").isZero();

        assertThat(get("/mp/trade/orders/" + orderNo, token).okData().path("payStatus").asText())
                .as("C 端详情要读得到欠费 —— 端上据此出「立即补缴」").isEqualTo("UNPAID");

        var blocked = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token);
        assertThat(blocked.status).as("欠费拦下次借出（响应：%s）", blocked.msg()).isEqualTo(409);

        post("/mp/trade/pay", Map.of("orderNo", orderNo, "scene", "RENT"), token).okData();
        assertThat(payStatus(orderNo)).as("补缴成功 → 已收").isEqualTo("PAID");
        assertThat(get("/mp/trade/orders/" + orderNo, token).okData().path("payStatus").asText()).isEqualTo("PAID");
        assertThat(shares(orderNo)).as("补缴之后分润补上").isPositive();
        assertThat(post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token).status)
                .as("还清了就能再借").isEqualTo(200);
    }

    @Test
    @DisplayName("已收的单不能再付 —— 此前补缴接口可对同一张单重复收钱")
    void paidOrderCannotBeChargedTwice() {
        String cab = liveCabinet();
        String token = consumerToken();
        String orderNo = rentAndReturn(token, cab);

        var again = post("/mp/trade/pay", Map.of("orderNo", orderNo, "scene", "RENT"), token);
        assertThat(again.status).as("响应：%s", again.msg()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE order_no=?", Integer.class, orderNo))
                .as("只有结算那一笔").isEqualTo(1);
    }

    @Test
    @DisplayName("App 买断也请款并分润 —— 此前这条路不发结算事件，那笔收入不分润")
    void appBuyoutIsCollectedAndShared() {
        String cab = liveCabinet();
        String token = consumerToken();
        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        post("/mp/trade/orders/" + orderNo + "/buyout", Map.of(), token).okData();

        assertThat(payStatus(orderNo)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT type FROM pay_order WHERE order_no=?", String.class, orderNo))
                .isEqualTo("BUYOUT");
        assertThat(shares(orderNo)).as("买断也是收入").isPositive();
    }

    // —— 夹具 ——

    private String rentAndReturn(String token, String cab) {
        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));
        BigDecimal payable = jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo);
        assertThat(payable).as("这条用例要有钱可收才说明问题").isPositive();
        return orderNo;
    }

    private String payStatus(String orderNo) {
        return jdbc.queryForObject("SELECT pay_status FROM ord_order WHERE order_no=?", String.class, orderNo);
    }

    private int shares(String orderNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM share_record WHERE order_no=?", Integer.class, orderNo);
        return n == null ? 0 : n;
    }

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    /** 独立站点 + 场地合同（费率 0.2，保证有分润可生成）+ 一台在线有宝的柜。 */
    private String liveCabinet() {
        String site = "STAR" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "欠费测试站点 " + site);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', ?, ?, 0.2, ?, ?, 'ACTIVE')",
                // 合同从上月初生效：本类测的是收款，不让合同生效日成为变量（按结算日找合同见 ShareMidMonthContractTest）
                "CTAR" + rnd(), venue, site, bizToday().withDayOfMonth(1).minusMonths(1).toString(),
                bizToday().plusYears(1).toString());
        String cab = "CBAR" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total,"
                + " available_count, status, online_status, last_heartbeat_at)"
                + " VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 6, 'DEPLOYED', 'ONLINE', NOW(3))", cab, cab, site);
        return cab;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
