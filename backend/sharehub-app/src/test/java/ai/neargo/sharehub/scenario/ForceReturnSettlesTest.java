package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.LegacyShareParams;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import ai.neargo.sharehub.trade.dto.InterventionDtos;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营「强制归还」走正常结算（2026-09-28 拍板）。
 *
 * <p>此前 {@code force_return} 直接把订单写成 SETTLED：不计费、不请款、不发结算事件 ——
 * 用户用了三个小时，账上是 0；场地方分不到钱；订单也不在收款态里（既不算已收也不算欠费）。
 * 要免单的话另走「减免」（waive），那是有留痕的决定，而不是强制归还的副作用。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@LegacyShareParams
class ForceReturnSettlesTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RentOrderService rents;

    final List<String> cabinets = new ArrayList<>();

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
    }

    @Test
    @DisplayName("使用中的单强制归还：按实际时长计费、请款、分润，并留干预流水")
    void inUseOrderIsBilledCollectedAndShared() {
        String cab = liveCabinet();
        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), consumerToken())
                .okData().path("orderNo").asText();
        jdbc.update("UPDATE ord_order SET rent_start_at = DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 3 HOUR),"
                + " '%Y-%m-%dT%H:%i:%s') WHERE order_no=?", orderNo);

        rents.intervene(orderNo, new InterventionDtos.InterveneReq("force_return", "柜机离线，用户已当面归还", null, "tester"));

        assertThat(str("status", orderNo)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo))
                .as("用了三个小时就该收三个小时的钱 —— 此前强制归还一律 0").isPositive();
        assertThat(str("pay_status", orderNo)).as("与正常归还同一条收款路径").isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM share_record WHERE order_no=?", Integer.class, orderNo))
                .as("收到的钱照常分润").isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_intervention WHERE order_no=? AND action='force_return'",
                Integer.class, orderNo)).as("干预仍要留痕").isEqualTo(1);
    }

    @Test
    @DisplayName("出宝中的单强制归还：宝没到用户手上，0 元结清（已收、不欠费）")
    void dispensingOrderSettlesAtZero() {
        String cab = liveCabinet();
        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), consumerToken())
                .okData().path("orderNo").asText();
        jdbc.update("UPDATE ord_order SET status='DISPENSING' WHERE order_no=?", orderNo);

        rents.intervene(orderNo, new InterventionDtos.InterveneReq("force_return", "弹仓失败，用户没拿到宝", null, "tester"));

        assertThat(str("status", orderNo)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no=?", BigDecimal.class, orderNo))
                .isEqualByComparingTo("0");
        assertThat(str("pay_status", orderNo)).as("0 元也要落收款态，否则既不算已收也不算欠费").isEqualTo("PAID");
    }

    // —— 夹具 ——

    private String str(String col, String orderNo) {
        return jdbc.queryForObject("SELECT " + col + " FROM ord_order WHERE order_no=?", String.class, orderNo);
    }

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    private String liveCabinet() {
        String site = "STFR" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "强制归还测试站点 " + site);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', ?, ?, 0.2, ?, ?, 'ACTIVE')",
                "CTFR" + rnd(), venue, site, bizToday().minusMonths(1).toString(), bizToday().plusYears(1).toString());
        String cab = "CBFR" + rnd();
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
