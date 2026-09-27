package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按订单支付：**金额只能由服务端从订单上取**。
 *
 * <p>此前端点是「body 里有 {@code amount} 就用它」，而 {@code PaymentServiceImpl.pay}
 * 只做 {@code requirePositive(amount)}、<b>从不与订单应付比对</b> ——
 * {@code POST /mp/trade/pay {orderNo, amount: 0.01}} 就能给一张应付十几块的单
 * 记一笔 0.01 的支付。而幂等键是 {@code "PAY-" + orderNo}：
 * <b>先到的那笔把这张单的支付金额锁死</b>，第二次调用原样返回第一笔。
 *
 * <p>隔壁 {@code MpUserController.recharge} 的类注释早把这条规矩写清楚了：
 * 「收前端传的金额，『充 1 元到账 100』就是一次普通的改参数请求」——
 * 同一个仓库里，充值守住了，按单支付没守。
 *
 * <p>顺带 {@code scene}：{@code pay_order.type} 的列注释声明的词表是
 * {@code DEPOSIT/RENT/BUYOUT}，而端点缺省写的是 {@code "ORDER"}（不在词表里），
 * c-app 发的又是小写 —— 同一列三种写法混着落，按用途筛支付单一条都筛不出来。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerPayAmountTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RentOrderService rents;
    /** 结算时已自动请款（C-BL-04）：要测补缴，得先让结算那一笔失败，造出一张欠费单。 */
    @Autowired
    ai.neargo.sharehub.trade.pay.StubPaymentPort stub;

    final List<String> cabinets = new ArrayList<>();

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
    }

    @Test
    @DisplayName("★★ 请求体里的 amount 一律不作数 —— 记的是订单应付")
    void requestAmountIsIgnored() {
        String cab = liveCabinet();
        String token = consumerToken();
        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        stub.declineNextFor(orderNo);
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));

        BigDecimal payable = jdbc.queryForObject(
                "SELECT amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo);
        assertThat(payable).as("这条用例要有钱可付才说明问题").isPositive();

        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", orderNo);
        body.put("scene", "RENT");
        body.put("amount", new BigDecimal("0.01"));   // 冒充「我只付一分钱」
        post("/mp/trade/pay", body, token).okData();

        assertThat(jdbc.queryForObject("SELECT amount FROM pay_order WHERE order_no=? AND status='PAID'", BigDecimal.class, orderNo))
                .as("落库的支付金额必须是订单应付 —— 收请求体里的金额等于让用户自己定价，"
                        + "而幂等键是 PAY-<orderNo>，先到的那笔就把这张单锁死了")
                .isEqualByComparingTo(payable);
    }

    @Test
    @DisplayName("scene 归一到 pay_order.type 的词表；不认识的值拒而不是悄悄落进去")
    void sceneIsNormalisedToTheColumnVocabulary() {
        String cab = liveCabinet();
        String token = consumerToken();
        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        stub.declineNextFor(orderNo);
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));

        // c-app 历史上发的是小写 —— 归一成大写，而不是原样落进一个声明了大写词表的列
        post("/mp/trade/pay", Map.of("orderNo", orderNo, "scene", "rent"), token).okData();
        assertThat(jdbc.queryForObject("SELECT type FROM pay_order WHERE order_no=? AND status='PAID'", String.class, orderNo))
                .isEqualTo("RENT");

        String other = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        rents.returnOrderAt(other, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));
        var resp = post("/mp/trade/pay", Map.of("orderNo", other, "scene", "membership"), token);
        assertThat(resp.code()).as("词表外的值应当拒（响应：%s）", resp.msg()).isNotZero();
    }

    // —— 夹具 ——

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    private String liveCabinet() {
        String site = "STPA" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "支付金额测试站点 " + site);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', ?, ?, 0.2, ?, ?, 'ACTIVE')",
                "CTPA" + rnd(), venue, site, bizToday().minusDays(1).toString(), bizToday().plusYears(1).toString());
        String cab = "CBPA" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total,"
                + " available_count, status, online_status, last_heartbeat_at)"
                + " VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 3, 'DEPLOYED', 'ONLINE', NOW(3))", cab, cab, site);
        return cab;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
