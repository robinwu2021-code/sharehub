package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import ai.neargo.sharehub.trade.service.RentOrderService;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 免单白名单与账单明细 —— 两件都是「东西全建好了，就是没人用」。
 *
 * <h3>免单白名单一次都没生效过</h3>
 * {@code usr_free_whitelist} 有表、有实体、有服务、有运营端授予/撤销端点，
 * {@code ord_order.free_reason} 的列注释写着「源 usr_free_whitelist.reason」，
 * {@code ChargeChain} 的免单分支连边界用例都写好了 ——
 * 而 {@code setFreeReason} 在全仓库出现 <b>0 次</b>。
 * 于是给 VIP 授了免单他照样全额付费，运营端「免费订单」那页永远是空的。
 *
 * <h3>账单明细加不出实付</h3>
 * {@code FeeItemVO} 的注释和 c-app 的类型定义都写着「逐项相加要等于实付」，
 * 而 RENT 那一行取的是 {@code feeAmount}（<b>折后</b>应付），下面又把减免记成负数行。
 * 免费单于是只剩一行「优惠减免 −24」，读起来像平台欠用户 24。
 * c-app 的 mock 种子（RENT 18 / WAIVE −3 / feeAmount 15）一直是对的 ——
 * <b>口径写在 mock 里，真后端没照做</b>，切过去才会暴露。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreeWhitelistBillingTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RentOrderService rents;

    final List<String> cabinets = new ArrayList<>();
    final List<String> whitelists = new ArrayList<>();

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        for (String w : whitelists) jdbc.update("DELETE FROM usr_free_whitelist WHERE whitelist_no=?", w);
    }

    @Test
    @DisplayName("授了白名单 → 下单定格 free_reason → 结算全免；没授的照常收费")
    void whitelistedUserRentsFree() {
        String cab = liveCabinet();
        String vip = user(), ordinary = user();
        grant(vip, "VIP", "UNLIMITED", "0", null, null);

        String freeOrder = rentAndReturn(vip, cab);
        String paidOrder = rentAndReturn(ordinary, cab);

        assertThat(jdbc.queryForObject("SELECT free_reason FROM ord_order WHERE order_no=?", String.class, freeOrder))
                .as("下单时就该定格 —— 此前这一列从来没有人写").isEqualTo("VIP");
        assertThat(amount(freeOrder)).isEqualByComparingTo("0");
        assertThat(waived(freeOrder)).as("免单是「先算出来再减免」，不是跳过计费").isPositive();

        assertThat(jdbc.queryForObject("SELECT free_reason FROM ord_order WHERE order_no=?", String.class, paidOrder))
                .isNull();
        assertThat(amount(paidOrder)).isPositive();
    }

    @Test
    @DisplayName("TIMES 额度用完就恢复收费，且 used_value 真的在涨")
    void timesQuotaRunsOut() {
        String cab = liveCabinet();
        String cUser = user();
        String wl = grant(cUser, "BD_DEMO", "TIMES", "2", null, null);

        String first = rentAndReturn(cUser, cab);
        String second = rentAndReturn(cUser, cab);
        String third = rentAndReturn(cUser, cab);

        assertThat(amount(first)).isEqualByComparingTo("0");
        assertThat(amount(second)).isEqualByComparingTo("0");
        assertThat(amount(third)).as("额度 2 次用完，第 3 单照常收费").isPositive();
        assertThat(jdbc.queryForObject("SELECT used_value FROM usr_free_whitelist WHERE whitelist_no=?",
                BigDecimal.class, wl)).as("不涨的话额度是个摆设").isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("AMOUNT 额度只免得起剩余那么多，余额照收 —— 额度 100 不该免出 130")
    void amountQuotaCapsTheWaiver() {
        String cab = liveCabinet();
        String cUser = user();
        // 额度只留 1 块，任何一单的应收都远大于它
        String wl = grant(cUser, "INTERNAL_TEST", "AMOUNT", "1", "AED", null);

        String orderNo = rentAndReturn(cUser, cab);

        assertThat(waived(orderNo)).as("只免得起 1").isEqualByComparingTo("1");
        assertThat(amount(orderNo)).as("剩下的照收").isPositive();
        assertThat(jdbc.queryForObject("SELECT used_value FROM usr_free_whitelist WHERE whitelist_no=?",
                BigDecimal.class, wl)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("过期 / 撤销的白名单不免单 —— 免单是钱，失效必须真的失效")
    void expiredOrRevokedWhitelistDoesNotApply() {
        String cab = liveCabinet();
        String expired = user(), revoked = user();
        grant(expired, "VIP", "UNLIMITED", "0", null, LocalDate.now().minusDays(1));
        String wl = grant(revoked, "VIP", "UNLIMITED", "0", null, null);
        jdbc.update("UPDATE usr_free_whitelist SET status='REVOKED' WHERE whitelist_no=?", wl);

        for (String u : List.of(expired, revoked)) {
            String orderNo = rentAndReturn(u, cab);
            assertThat(jdbc.queryForObject("SELECT free_reason FROM ord_order WHERE order_no=?", String.class, orderNo))
                    .as("用户 %s 不该拿到免单", u).isNull();
            assertThat(amount(orderNo)).isPositive();
        }
    }

    @Test
    @DisplayName("账单明细逐项相加 = 实付（免单单与用券单各验一次）")
    void theBillAddsUp() {
        String cab = liveCabinet();
        String token = consumerToken();
        String probe = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        String cUser = jdbc.queryForObject("SELECT c_user_no FROM ord_order WHERE order_no=?", String.class, probe);
        settle(probe, cab);

        // ① 正常单：只有租借费（押金 0 不出行）
        assertRentLinesAddUp(probe, token);

        // ② 免单单：RENT 是**折扣前**应收，WAIVE 记负数，两项相加 = 实付 0
        grant(cUser, "VIP", "UNLIMITED", "0", null, null);
        String freeOrder = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        settle(freeOrder, cab);

        List<JsonNode> fees = assertRentLinesAddUp(freeOrder, token);
        assertThat(fees).as("免费单只剩一行「优惠减免 −24」的话，读起来像平台欠用户 24").hasSizeGreaterThan(1);
        assertThat(fees.stream().map(f -> f.path("type").asText()).toList()).contains("RENT", "WAIVE");
    }

    /** RENT + COUPON + WAIVE 必须等于订单实付；返回这几行供进一步断言。 */
    private List<JsonNode> assertRentLinesAddUp(String orderNo, String token) {
        JsonNode detail = get("/mp/trade/orders/" + orderNo, token).okData();
        List<JsonNode> lines = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode f : detail.path("fees")) {
            String type = f.path("type").asText();
            if (List.of("RENT", "COUPON", "WAIVE").contains(type)) {
                lines.add(f);
                sum = sum.add(f.path("amount").decimalValue());
            }
        }
        assertThat(sum).as("订单 %s 的明细加不出实付（明细=%s）", orderNo, detail.path("fees"))
                .isEqualByComparingTo(amount(orderNo));
        return lines;
    }

    /**
     * 运营端「免费订单」页此前**永远是空的**：{@code FreeOrderServiceImpl} 写死返回
     * 空结果集与零统计，注释说在等 {@code ord_order} 补 v2 新列 ——
     * 那些列早就补齐了，没人回来接线。空列表和「这个月确实没人用免单」长得一模一样。
     */
    @Test
    @DisplayName("授出去的免单，运营端「免费订单」页查得到，页头统计也不再是 0")
    void opsCanSeeTheFreeOrdersItGranted() {
        String cab = liveCabinet();
        String cUser = user();
        grant(cUser, "MERCHANT_SELF", "UNLIMITED", "0", null, null);
        String orderNo = rentAndReturn(cUser, cab);

        String token = login("ADMIN");
        JsonNode rows = get("/api/trade/free-orders?page=1&size=50&keyword=" + cUser, token).okData();
        assertThat(rows.path("total").asLong()).as("查不到就等于这一页从来没上线过").isPositive();
        JsonNode row = rows.path("list").path(0);
        assertThat(row.path("orderNo").asText()).isEqualTo(orderNo);
        assertThat(row.path("whitelistReason").asText()).isEqualTo("MERCHANT_SELF");
        assertThat(row.path("waivedAmount").decimalValue()).as("减免额要出，这页的意义就是成本管控").isPositive();

        JsonNode stats = get("/api/trade/free-orders/stats", token).okData();
        assertThat(stats.path("monthCount").asLong()).isPositive();
        assertThat(stats.path("waivedTotal").decimalValue()).isPositive();
    }

    // —— 夹具 ——

    private void settle(String orderNo, String cab) {
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));
    }

    private String rentAndReturn(String cUserNo, String cab) {
        String orderNo = rents.rent(cUserNo, cab, true, null).orderNo();
        settle(orderNo, cab);
        return orderNo;
    }

    private BigDecimal amount(String orderNo) {
        return jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo);
    }

    private BigDecimal waived(String orderNo) {
        return jdbc.queryForObject("SELECT waived_amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo);
    }

    private String grant(String cUserNo, String reason, String quotaType, String quotaValue,
                         String currency, LocalDate validTo) {
        String no = "UFW" + rnd();
        whitelists.add(no);
        jdbc.update("INSERT INTO usr_free_whitelist (whitelist_no, tenant_id, c_user_no, reason, quota_type,"
                        + " quota_value, used_value, currency, valid_to, status)"
                        + " VALUES (?, 'MAIN', ?, ?, ?, ?, 0, ?, ?, 'ACTIVE')",
                no, cUserNo, reason, quotaType, quotaValue,
                currency == null ? "AED" : currency, validTo == null ? null : validTo.toString());
        return no;
    }

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    private String user() {
        return "CU-FW-" + rnd();
    }

    private String liveCabinet() {
        String site = site();
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), ?, 0.2, ?, ?, 'ACTIVE')",
                "CTFW" + rnd(), site, site, LocalDate.now().minusDays(1).toString(), LocalDate.now().plusYears(1).toString());
        String cab = "CBFW" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total,"
                + " available_count, status, online_status, last_heartbeat_at)"
                + " VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 3, 'DEPLOYED', 'ONLINE', NOW(3))", cab, cab, site);
        return cab;
    }

    private String site() {
        String site = "STFW" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "免单测试站点 " + site);
        return site;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
