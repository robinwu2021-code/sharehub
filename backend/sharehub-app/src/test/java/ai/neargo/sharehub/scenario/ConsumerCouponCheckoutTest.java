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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确认页上的两个选择，此前**一个都没传到后端**。
 *
 * <ul>
 *   <li>「免押金 / 支付押金」—— 控制器只读 {@code cabinetNo}，选哪个都记 50 押金。
 *       选免押的人先被冻结一笔预授权，订单上又记一笔押金，同一笔钱出现两次；</li>
 *   <li>「用券」—— 契约里有 {@code couponNo} 字段，后端不读、页面也没有选券入口。
 *       券领得到、看得见、永远花不掉。</li>
 * </ul>
 *
 * <p>两件事都不报错，所以只有用户会发现，而且发现时钱已经扣了。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerCouponCheckoutTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RentOrderService rents;
    @Autowired
    ai.neargo.sharehub.api.core.port.CouponUsePort couponUse;

    final List<String> cabinets = new ArrayList<>();
    final List<String> tpls = new ArrayList<>();
    final List<String> coupons = new ArrayList<>();

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        for (String c : coupons) jdbc.update("DELETE FROM usr_coupon WHERE coupon_no=?", c);
        for (String t : tpls) jdbc.update("DELETE FROM coupon_tpl WHERE tpl_no=?", t);
    }

    @Test
    @DisplayName("免押下单不记押金，押金下单记 50 —— 此前两者都记 50")
    void depositChoiceReachesTheOrder() {
        String cab = liveCabinet();
        String free = rents.rent(user(), cab, true, null).orderNo();
        String paid = rents.rent(user(), cab, false, null).orderNo();

        assertThat(deposit(free))
                .as("免押的额度冻结在 pay_auth 上，订单再记一笔就是同一笔钱说两遍")
                .isEqualByComparingTo("0");
        assertThat(deposit(paid)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("用券下单 → 结算抵扣、券核销并回指订单、抵扣额落列")
    void couponIsBoundAtCheckoutAndConsumedAtSettlement() {
        String cab = liveCabinet();
        String cUser = user();
        BigDecimal plain = settledAmount(rentAndReturn(cUser, cab, null));
        assertThat(plain).as("这条用例要有钱可抵才说明问题；种子费率算出 0 的话先修费率").isPositive();

        String coupon = coupon(cUser, cut("3"), null);
        String orderNo = rentAndReturn(cUser, cab, coupon);

        BigDecimal off = plain.min(new BigDecimal("3"));
        assertThat(settledAmount(orderNo)).isEqualByComparingTo(plain.subtract(off));
        assertThat(jdbc.queryForObject("SELECT coupon_amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo))
                .as("抵扣额要落列：模板面额会改、折扣额还取决于当时应收，事后推不回来")
                .isEqualByComparingTo(off);
        assertThat(jdbc.queryForMap("SELECT status, used_order_no FROM usr_coupon WHERE coupon_no=?", coupon))
                .containsEntry("status", "USED").containsEntry("used_order_no", orderNo);
    }

    @Test
    @DisplayName("门槛不够的券不核销 —— 券留在手里，不是白烧掉")
    void couponBelowThresholdIsLeftUntouched() {
        String cab = liveCabinet();
        String cUser = user();
        // 门槛设成一个远超任何试算金额的数，确保这一单永远够不着
        String coupon = coupon(cUser, cut("3", "99999"), null);
        String orderNo = rentAndReturn(cUser, cab, coupon);

        assertThat(jdbc.queryForObject("SELECT coupon_amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT status FROM usr_coupon WHERE coupon_no=?", String.class, coupon))
                .as("没抵到钱就不该核销，否则用户的券凭空消失")
                .isEqualTo("UNUSED");
    }

    @Test
    @DisplayName("别人的券 / 已用的券 / 过期的券 → 当场拒单，不是静默按原价")
    void unusableCouponIsRejectedAtCheckout() {
        String cab = liveCabinet();
        String mine = user(), other = user();
        String hisCoupon = coupon(other, cut("3"), null);
        String usedCoupon = coupon(mine, cut("3"), null);
        jdbc.update("UPDATE usr_coupon SET status='USED' WHERE coupon_no=?", usedCoupon);
        String staleCoupon = coupon(mine, cut("3"), LocalDate.now().minusDays(1));

        for (String bad : List.of(hisCoupon, usedCoupon, staleCoupon, "CP-NOT-EXIST")) {
            assertThatThrownBy(() -> rents.rent(mine, cab, true, bad))
                    .as("券 %s 应当拒单", bad)
                    .hasMessage("error.rent.coupon_unusable");
        }
    }

    /**
     * 核销是**带条件的原子更新**，它的返回值就是并发裁决。
     *
     * <p>单独测它，是因为上一条（两单抢一张券）根本测不到这里：等第二单走到结算时，
     * {@code couponOf} 的重新校验已经先一步发现券是 USED 了，{@code consume} 压根没被调用。
     * 负对照实测 —— 把 {@code eq(status, UNUSED)} 整条拆掉，那条用例照样全绿。
     * <b>两道保护叠在一起，外面那道把里面那道遮住了。</b>
     */
    @Test
    @DisplayName("核销只有第一次成功 —— 条件更新是并发下唯一的裁决点")
    void consumeIsTheAtomicArbiter() {
        String cUser = user();
        String coupon = coupon(cUser, cut("3"), null);

        assertThat(couponUse.consume(coupon, "ORD-FIRST")).as("第一次抢到").isTrue();
        assertThat(couponUse.consume(coupon, "ORD-SECOND"))
                .as("第二次必须失败；返回 true 就意味着同一张券抵了两单，而且两单都不报错")
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT used_order_no FROM usr_coupon WHERE coupon_no=?", String.class, coupon))
                .as("回指第一单，不该被后来者覆盖").isEqualTo("ORD-FIRST");
    }

    /**
     * 两单挂同一张券时，后结算的那单按原价。
     *
     * <p>注意这条**测的是结算时的重新校验**（{@code couponOf} 发现券已 USED），
     * 不是 {@code consume} 的并发裁决 —— 后者在这条路径上根本走不到，见
     * {@link #consumeIsTheAtomicArbiter}。
     */
    @Test
    @DisplayName("券已被核销 → 后结算的那单重新校验后按原价，不是少收钱")
    void secondOrderRevalidatesAndChargesFullPrice() {
        String cab = liveCabinet();
        String cUser = user();
        String coupon = coupon(cUser, cut("3"), null);

        // 两单都在「校验通过」的时刻抢到了这张券（下单只绑定不核销）
        String first = rents.rent(cUser, cab, true, coupon).orderNo();
        jdbc.update("UPDATE ord_order SET coupon_no=? WHERE order_no=?", coupon,
                rents.rent(cUser, cab, true, null).orderNo());
        String second = jdbc.queryForObject(
                "SELECT order_no FROM ord_order WHERE coupon_no=? AND order_no<>? ORDER BY id DESC LIMIT 1",
                String.class, coupon, first);

        BigDecimal firstOff = settleAndReadOff(first, cab);
        BigDecimal secondOff = settleAndReadOff(second, cab);

        assertThat(firstOff).as("先结算的抵到").isPositive();
        assertThat(secondOff).as("券已被核销，这一单必须按原价 —— 不重算就是钱少收了").isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT used_order_no FROM usr_coupon WHERE coupon_no=?", String.class, coupon))
                .isEqualTo(first);
    }

    /**
     * 端点这一层单独守一道。
     *
     * <p>上面几条都直接调 service —— 而**原来的缺陷恰恰在控制器里**：
     * 它只从请求体里读 {@code cabinetNo}，另外两个字段连读都没读。
     * 只测 service 的话，谁把控制器改回去，这些用例照样全绿。
     */
    @Test
    @DisplayName("请求体里的 useFreeDeposit / couponNo 真的从 HTTP 传到了订单上")
    void requestBodyFieldsSurviveTheController() {
        String cab = liveCabinet();
        String token = consumerToken();

        // 先借一单拿到会话里的 c_user_no —— 手机号在库里是脱敏存的，反查不如就地读
        String probe = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        String cUser = jdbc.queryForObject("SELECT c_user_no FROM ord_order WHERE order_no=?", String.class, probe);
        assertThat(deposit(probe)).as("不传 useFreeDeposit 时按确认页的默认值（免押）").isEqualByComparingTo("0");

        String coupon = coupon(cUser, cut("3"), null);
        String orderNo = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cab, "useFreeDeposit", false, "couponNo", coupon), token)
                .okData().path("orderNo").asText();

        assertThat(deposit(orderNo)).as("选了「支付押金」就该记 50").isEqualByComparingTo("50");
        assertThat(jdbc.queryForObject("SELECT coupon_no FROM ord_order WHERE order_no=?", String.class, orderNo))
                .as("选的券要挂到单上，否则结算时无从抵扣").isEqualTo(coupon);
    }

    /** 用不了的券要在 HTTP 这一层就以业务失败回去，而不是 200 + 悄悄按原价。 */
    @Test
    @DisplayName("端点对不可用的券返回业务失败，不是 200")
    void controllerRejectsUnusableCoupon() {
        String cab = liveCabinet();
        var resp = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cab, "couponNo", "CP-NOT-EXIST"), consumerToken());

        assertThat(resp.code()).as("业务码应为失败（响应：%s）", resp.msg()).isNotZero();
    }

    // —— 夹具 ——

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }


    private BigDecimal settleAndReadOff(String orderNo, String cab) {
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));
        return jdbc.queryForObject("SELECT coupon_amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo);
    }

    /** 借出 → 3 小时后归还并结算，返回订单号。 */
    private String rentAndReturn(String cUserNo, String cab, String couponNo) {
        String orderNo = rents.rent(cUserNo, cab, true, couponNo).orderNo();
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));
        return orderNo;
    }

    private BigDecimal settledAmount(String orderNo) {
        return jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo);
    }

    private BigDecimal deposit(String orderNo) {
        return jdbc.queryForObject("SELECT deposit_amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo);
    }

    private String cut(String value) {
        return cut(value, "0");
    }

    /** 建一张满减券模板，返回模板号。 */
    private String cut(String value, String threshold) {
        String tpl = "CTPLCP" + rnd();
        tpls.add(tpl);
        jdbc.update("INSERT INTO coupon_tpl (tpl_no, tenant_id, name, type, value, threshold, currency, stock, issued, status)"
                + " VALUES (?, 'MAIN', ?, 'CUT', ?, ?, 'AED', 0, 0, 'ACTIVE')", tpl, "用券结算测试 " + tpl, value, threshold);
        return tpl;
    }

    /** 把模板发一张到某人手里，返回券号。{@code expireAt} 为 null 表示不限期。 */
    private String coupon(String cUserNo, String tplNo, LocalDate expireAt) {
        String no = "CPT" + rnd();
        coupons.add(no);
        jdbc.update("INSERT INTO usr_coupon (coupon_no, tenant_id, c_user_no, tpl_no, status, expire_at)"
                        + " VALUES (?, 'MAIN', ?, ?, 'UNUSED', ?)",
                no, cUserNo, tplNo, expireAt == null ? null : expireAt.atStartOfDay());
        return no;
    }

    private String user() {
        return "CU-CP-" + rnd();
    }

    private String liveCabinet() {
        String site = site();
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), ?, 0.2, ?, ?, 'ACTIVE')",
                "CTCP" + rnd(), site, site, LocalDate.now().minusDays(1).toString(), LocalDate.now().plusYears(1).toString());
        String cab = "CBCP" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count,"
                + " status, online_status, last_heartbeat_at) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 3, 'DEPLOYED', 'ONLINE', NOW(3))",
                cab, cab, site);
        return cab;
    }

    private String site() {
        String site = "STCP" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "用券结算测试站点 " + site);
        return site;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
