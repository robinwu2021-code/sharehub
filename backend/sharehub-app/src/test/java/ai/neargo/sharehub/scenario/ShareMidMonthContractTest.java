package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.finance.service.ShareGenerator;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 月中签约的站点，签约当月的订单也要分润（2026-09-28 拍板）。
 *
 * <p>此前分润按「结算月 1 号」找生效合同（{@code period + "-01"}）：9-15 签约的站点，
 * 9 月的单一律查不到合同 —— 场地方整月拿不到钱，而日志只有一句「没有可用比例」。
 * 现在按每单的<b>结算日</b>（{@code OrderSettledEvent.settledOn}，业务日历）找合同。
 */
class ShareMidMonthContractTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ShareGenerator generator;

    @Test
    @DisplayName("合同今天才生效：今天结算的单照样分给场地方")
    void contractStartingMidMonthCoversOrdersSettledAfterIt() {
        // 月初那天合同还没生效，才测得出「按 1 号找」与「按结算日找」的差别
        assertThat(bizToday().getDayOfMonth()).as("今天得不是 1 号").isGreaterThan(1);

        String site = "STMM" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "月中签约站点 " + site);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', ?, ?, 0.2, ?, ?, 'ACTIVE')",
                "CTMM" + rnd(), venue, site, bizToday().toString(), bizToday().plusYears(1).toString());

        String orderNo = "ORDMM" + rnd();
        int n = generator.generate(new OrderSettledEvent(orderNo, null, site, null, new BigDecimal("50.00"), "AED",
                bizToday().toString().substring(0, 7), bizToday().toString()));

        assertThat(n).as("签约当天之后结算的单要分润 —— 此前按当月 1 号找合同，整月落空").isPositive();
        assertThat(jdbc.queryForObject("SELECT amount FROM share_record WHERE order_no=? AND dimension='VENUE'",
                BigDecimal.class, orderNo)).isEqualByComparingTo("10.00");
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
