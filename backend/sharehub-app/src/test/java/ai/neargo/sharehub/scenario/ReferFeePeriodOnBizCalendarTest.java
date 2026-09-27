package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixedBizClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 牵线费的**归属月**按业务日历，不按服务器时区。
 *
 * <h2>为什么单独一个类</h2>
 * 这条用例要把时钟钉在业务日历的 10-01。而同一批的
 * {@link ReferFeeOnContractSignedTest} 用 {@code bizToday()} 造合同日期 ——
 * 那个 helper 直接算 {@code LocalDate.now(Asia/Dubai)}，**不走 BizClock**，
 * 所以钉住时钟只会让它们的合同日期与服务端认为的「今天」错开，把那些用例弄红。
 *
 * <p>钉时钟影响的只是**问 BizClock 要日期**的那些代码（这里是牵线费生成器）；
 * 合同自己的日期校验走的是 {@code LocalDate.now(bizZone)}，不受影响 ——
 * 所以合同照常签得下来，而牵线费落到 10 月。这个不对称正是本用例成立的前提。
 *
 * <h2>修之前是什么后果</h2>
 * {@code period} 取 {@code LocalDate.now()}（服务器时区，生产是 {@code Asia/Shanghai}）。
 * 迪拜 9-30 20:00 之后签的合同，归属月直接变成 {@code 2026-10} ——
 * **每月有 4 小时签的合同，牵线费记错月份**，而结算单上看不出任何异常。
 * 同一处还有 {@code onDate}：用它筛「这天生效的责任行」，错一天会把
 * 有效期到 9-30 的牵线人漏掉，那笔钱谁也拿不到，而日志只会说「没配对价」。
 */
@Import(FixedBizClock.class)
class ReferFeePeriodOnBizCalendarTest extends ApiTestSupport {

    private static final String REFERRER = "AG005";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FixedBizClock.MutableClock clock;

    @Test
    @DisplayName("★ 业务日历跨月的那一瞬签约 → 牵线费记在业务月，不是服务器月")
    void period_follows_the_business_calendar() {
        // UTC 20:30 → 迪拜 2026-10-01 00:30。而真实日期（服务器月份）是别的月 ——
        // 两者不同才是这条用例的观察点；相同的话它什么也证明不了。
        clock.at = FixedBizClock.JUST_AFTER_BIZ_MIDNIGHT;
        String serverMonth = LocalDate.now().toString().substring(0, 7);
        assertThat(serverMonth)
                .as("前提：真实（服务器）月份必须与钉住的业务月 2026-10 不同，否则本用例无法区分对错")
                .isNotEqualTo("2026-10");

        String admin = login("ADMIN");
        String site = newSite(admin);
        long id = referrer(admin, site, 500);
        try {
            String no = signedContract(site, bizToday().minusDays(1));

            String period = jdbc.queryForObject(
                    "SELECT period FROM share_record WHERE source_no=? AND basis='REFER' AND deleted=0",
                    String.class, no);
            assertThat(period)
                    .as("牵线费的归属月该是业务日历的 2026-10，而不是服务器月 %s", serverMonth)
                    .isEqualTo("2026-10");
        } finally {
            jdbc.update("DELETE FROM share_record WHERE payee_no=?", REFERRER);
            drop(admin, site, id);
        }
    }

    // ——————————————————————— 夹具（形状同 ReferFeeOnContractSignedTest）———————————————————————

    private static final String VENUE = "VEN301";

    private String newSite(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "牵线费跨月测试站点");
        m.put("venueNo", VENUE);
        m.put("regionId", "R-REFER");
        m.put("openHours", "10:00-22:00");
        return post("/api/ops/sites", m, admin).okData().path("siteNo").asText();
    }

    private String signedContract(String site, LocalDate start) {
        String bd = login("BD"), admin = login("ADMIN");
        Map<String, Object> c = new HashMap<>();
        c.put("venueNo", VENUE);
        c.put("siteNo", site);
        c.put("shareRate", 0.2);
        c.put("entryFee", 1000);
        c.put("startAt", start.toString());
        c.put("endAt", start.plusYears(1).toString());
        c.put("currency", "AED");
        String no = post("/api/ops/contracts", c, bd).okData().path("contractNo").asText();
        post("/api/ops/contracts/" + no + "/submit", Map.of(), bd).okData();
        post("/api/ops/contracts/" + no + "/audit", Map.of("result", "APPROVE"), admin).okData();
        String fileNo = uploadFile("CONTRACT_SCAN", "scan.pdf", minimalPdf(), bd).path("fileNo").asText();
        post("/api/ops/contracts/" + no + "/sign",
                Map.of("signedAt", bizToday().toString(), "fileNos", List.of(fileNo)), bd).okData();
        return no;
    }

    private long referrer(String admin, String site, Object oneOff) {
        Map<String, Object> m = new HashMap<>();
        m.put("agentNo", REFERRER);
        m.put("role", "REFER");
        m.put("oneOffAmount", oneOff);
        return post("/api/ops/sites/" + site + "/agents", m, admin).okData().path("id").asLong();
    }

    private void drop(String admin, String site, long id) {
        post("/api/ops/sites/" + site + "/agents/" + id + "/remove", Map.of(), admin).okData();
    }
}
