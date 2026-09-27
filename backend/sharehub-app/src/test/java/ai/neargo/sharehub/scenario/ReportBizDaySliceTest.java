package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.operation.mapper.OperationMappers.OrderStatMapper;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报表 / 运营概览按<b>业务日</b>切日（2026-09-28 拍板：统一按业务时区）。
 *
 * <p>此前按 UTC 切：{@code LEFT(rent_end_at, 10)} 取的是 UTC 日期。迪拜 00:00–04:00 结束的单
 * （UTC 前一天 20:00–24:00）记到前一天 —— 而券、合同、分润归属都已经按业务日历，
 * 同一张单在概览里属于 8-10，在分润里属于 8-11。
 */
class ReportBizDaySliceTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrderStatMapper orders;

    @Test
    @DisplayName("UTC 8-10 21:30 结束的单 = 迪拜 8-11 01:30 → 记在 8-11")
    void orderEndingLateUtcCountsOnNextBizDay() {
        String site = "STBD" + rnd();
        String cab = "CBBD" + rnd();
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total,"
                + " available_count, status, online_status) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 6, 'DEPLOYED', 'OFFLINE')",
                cab, cab, site);
        jdbc.update("INSERT INTO ord_order (order_no, tenant_id, c_user_no, cabinet_no, site_no, status, device_type, currency,"
                        + " amount, rent_start_at, rent_end_at) VALUES (?, 'MAIN', 'CU-BD', ?, ?, 'SETTLED', 'POWERBANK', 'AED', 12.00,"
                        + " '2026-08-10T19:00:00', '2026-08-10T21:30:00')",
                "ORDBD" + rnd(), cab, site);
        try {
            Map<String, Object> onBizDay = orders.summaryOfSite(site, "2026-08-11", "2026-08-11");
            Map<String, Object> onUtcDay = orders.summaryOfSite(site, "2026-08-10", "2026-08-10");
            assertThat(((Number) onBizDay.get("orders")).intValue())
                    .as("迪拜时间 8-11 01:30 结束 —— 业务日是 8-11").isEqualTo(1);
            assertThat(((Number) onUtcDay.get("orders")).intValue())
                    .as("此前按 UTC 切，这张单被记在 8-10").isZero();
        } finally {
            jdbc.update("DELETE FROM ord_order WHERE c_user_no='CU-BD' AND cabinet_no=?", cab);
            jdbc.update("DELETE FROM dev_cabinet WHERE cabinet_no=?", cab);
        }
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
