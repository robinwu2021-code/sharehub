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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会员权益必须真的作用到账单上 —— 「有表、有实体、有运营端端点，唯独没人读」的第 N 例。
 *
 * <p>{@code mbr_benefit} 有表、有实体、有运营端配置端点，配权益时还带等级单调性校验；
 * {@code mbr_plan} 有表有实体、C 端有会员方案页；{@code ChargeChain} 的类注释甚至写着
 * 「券与<b>会员权益</b>在结算时才确定」—— 唯独 <b>{@code trade} 域对 membership 零引用</b>
 * （整个域 grep 不到一次）。于是给用户配了九折，他照样全额付费，
 * 而方案页上那行「租金九折」是从自由文案里读出来的，看起来一切正常。
 *
 * <p>本类钉三件事，都走**真实借还闭环**而不是构造 VO：
 * <ol>
 *   <li>折扣真的少收了钱，且金额落列（{@code member_discount_amount}）；</li>
 *   <li>免费时长真的少算了分钟，而 {@code duration_min} 仍记**真实时长**；</li>
 *   <li>折扣出现在费用明细里，且明细<b>逐项相加仍等于实付</b> ——
 *       新增一种折扣而不进那个和式，那笔优惠会被账单静默吞掉。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberRightsReachBillingTest extends ApiTestSupport {

    /** 权益等级用测试专属值，免得动到测试库里既有的 SILVER/GOLD/PLATINUM 配置。 */
    private static final String LEVEL = "TESTGOLD";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RentOrderService rents;

    final List<String> cabinets = new ArrayList<>();
    final List<String> memberships = new ArrayList<>();

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        for (String m : memberships) jdbc.update("DELETE FROM usr_membership WHERE mbr_no=?", m);
        jdbc.update("DELETE FROM mbr_benefit WHERE level=?", LEVEL);
    }

    /**
     * 同一台柜、同样时长，会员付的应当比非会员少 —— 差额正好是折扣额。
     *
     * <p>用**对照**而不是写死金额：测试库的计价方案随时可能改，
     * 写死数字的用例会因为改了价而红，那种红没有信息量。
     */
    @Test
    @DisplayName("会员九折：实付少一成，折扣额落列")
    void discountReachesTheSettledAmount() {
        String cab = liveCabinet();
        benefit(new BigDecimal("0.90"), 0);

        String plain = rentAndReturn(user(), cab);
        String cUser = user();
        member(cUser, null);
        String vip = rentAndReturn(cUser, cab);

        BigDecimal full = amount(plain);
        BigDecimal paid = amount(vip);
        assertThat(full).as("对照单必须有非零应收，否则本例证明不了任何事").isGreaterThan(BigDecimal.ZERO);

        assertThat(memberDiscount(vip))
                .as("折扣额要落列 —— 权益配置会改，事后推不出这一单折了多少")
                .isEqualByComparingTo(full.subtract(paid));
        assertThat(paid).as("九折 = 付 90%%（对照单 %s）", full)
                .isEqualByComparingTo(full.multiply(new BigDecimal("0.9")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(jdbc.queryForObject("SELECT member_level FROM ord_order WHERE order_no=?", String.class, vip))
                .as("等级要留痕，否则答不出「这单凭什么打折」").isEqualTo(LEVEL);
    }

    /**
     * 免费时长作用于**计费分钟数**，{@code duration_min} 仍记真实时长。
     *
     * <p>把免费时长从时长里抹掉，运营看到的「平均借用时长」就永久偏短 ——
     * 那是运营数据，不是账，而且不会有任何地方报错。
     */
    @Test
    @DisplayName("免费 60 分钟：3 小时的单按 2 小时计费，时长仍记 180")
    void freeMinutesCutBillingNotDuration() {
        String cab = liveCabinet();
        benefit(null, 60);

        String plain = rentAndReturn(user(), cab);
        String cUser = user();
        member(cUser, null);
        String vip = rentAndReturn(cUser, cab);

        assertThat(amount(vip)).as("少算 60 分钟就该少收钱（对照单 %s）", amount(plain))
                .isLessThan(amount(plain));
        assertThat(jdbc.queryForObject("SELECT duration_min FROM ord_order WHERE order_no=?", Integer.class, vip))
                .as("duration_min 是真实时长，不是计费时长").isEqualTo(180);
    }

    /**
     * 账单不变量：明细逐项相加 = 实付，且会员折扣**出现在明细里**。
     *
     * <p>不出现的表现是「租借费」直接显示折后价、账单上看不到会员两个字，
     * 而合计仍然对得上 —— 没有任何地方会报错，只有用户说不清优惠去哪了。
     */
    @Test
    @DisplayName("费用明细含会员折扣行，且逐项相加仍等于实付")
    void memberDiscountShowsUpInTheBill() {
        String cab = liveCabinet();
        benefit(new BigDecimal("0.80"), 0);

        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", java.util.Map.of("phone", phone), null).okData().path("devCode").asText();
        var login = post("/mp/auth/login",
                java.util.Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null).okData();
        String token = login.path("token").asText();
        member(login.path("cUserNo").asText(), null);

        String orderNo = post("/mp/trade/orders/rent",
                java.util.Map.of("cabinetNo", cab, "useFreeDeposit", true), token).okData().path("orderNo").asText();
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));

        var d = get("/mp/trade/orders/" + orderNo, token).okData();
        BigDecimal payable = new BigDecimal(d.path("feeAmount").asText("0"));
        BigDecimal sum = BigDecimal.ZERO;
        boolean hasMemberLine = false;
        for (var f : d.path("fees")) {
            if ("MEMBER".equals(f.path("type").asText())) hasMemberLine = true;
            sum = sum.add(new BigDecimal(f.path("amount").asText("0")));
        }

        assertThat(hasMemberLine).as("八折的单，明细里必须有会员折扣那一行（明细=%s）", d.path("fees")).isTrue();
        assertThat(sum.compareTo(payable))
                .as("""
                        明细逐项相加必须等于合计 —— 新增一种折扣就必须同时进那个和式，
                        否则那笔优惠会被账单静默吞掉。明细=%s 合计=%s""", d.path("fees"), payable)
                .isZero();
    }

    // —— 夹具 ——

    /** 配一档权益。{@code rentDiscount} 为 null = 不打折（列是 NOT NULL，写 1.00）。 */
    private void benefit(BigDecimal rentDiscount, int freeMinutes) {
        jdbc.update("DELETE FROM mbr_benefit WHERE level=?", LEVEL);
        jdbc.update("INSERT INTO mbr_benefit (tenant_id, level, name, rent_discount, free_minutes, deposit_free,"
                        + " monthly_coupons, points_rate, upgrade_points, status)"
                        + " VALUES ('MAIN', ?, ?, ?, ?, 0, 0, 1, 0, 'ENABLED')",
                LEVEL, "会员权益计费测试", rentDiscount == null ? BigDecimal.ONE : rentDiscount, freeMinutes);
    }

    /**
     * 给某人开一张 ACTIVE 会员。{@code planNo} 为 null = 只有等级权益，没有卡权益。
     *
     * <p>{@code plan_no} 在 DDL 里是 <b>NOT NULL</b>，所以「无方案」只能写空串 ——
     * 而 {@code MembershipServiceImpl.get} 的注释写着「planNo 可能为 null（自定义权益，无关联方案）」，
     * 两处口径不一致。此处按 DDL 走，那条注释另议。
     */
    private void member(String cUserNo, String planNo) {
        String no = "UMB" + rnd();
        memberships.add(no);
        jdbc.update("INSERT INTO usr_membership (mbr_no, tenant_id, c_user_no, plan_no, level, points,"
                        + " start_at, end_at, auto_renew, status)"
                        + " VALUES (?, 'MAIN', ?, ?, ?, 0, ?, ?, 0, 'ACTIVE')",
                no, cUserNo, planNo == null ? "" : planNo, LEVEL,
                bizToday().minusDays(1), bizToday().plusYears(1));
    }

    /** 借出 → 3 小时后归还并结算。 */
    private String rentAndReturn(String cUserNo, String cab) {
        String orderNo = rents.rent(cUserNo, cab, true, null).orderNo();
        rents.returnOrderAt(orderNo, cab, LocalDateTime.now(ZoneOffset.UTC).plusHours(3));
        return orderNo;
    }

    private BigDecimal amount(String orderNo) {
        return jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo);
    }

    private BigDecimal memberDiscount(String orderNo) {
        return jdbc.queryForObject("SELECT member_discount_amount FROM ord_order WHERE order_no=?",
                BigDecimal.class, orderNo);
    }

    private String user() {
        return "CU-MB-" + rnd();
    }

    private String liveCabinet() {
        String site = site();
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), ?, 0.2, ?, ?, 'ACTIVE')",
                "CTMB" + rnd(), site, site, bizToday().minusDays(1).toString(), bizToday().plusYears(1).toString());
        String cab = "CBMB" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count,"
                        + " status, online_status, last_heartbeat_at) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 3, 'DEPLOYED', 'ONLINE', NOW(3))",
                cab, cab, site);
        return cab;
    }

    private String site() {
        String site = "STMB" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "会员计费测试站点 " + site);
        return site;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
