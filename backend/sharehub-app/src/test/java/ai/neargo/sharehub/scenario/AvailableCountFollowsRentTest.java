package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门店卡上的「可借 N」跟着借还走（2026-09-28）。
 *
 * <p>真机实测：从一台写着「可借 8」的柜子借走一个宝，卡上还是 8。查下来机制其实是对的
 * （{@code PowerbankTracker#recount} 在借出/归还后按在柜宝数回写 {@code dev_cabinet.available_count}），
 * <b>但整个库里一条充电宝台账都没有</b> —— 种子只建柜不建宝，于是每次借出都走「该柜没有宝台账」
 * 那条兼容分支，数字永远不动。数字不动本身还不是最糟的：它是**用户据以决定跑不跑这一趟**的数。
 *
 * <p>本类钉住有台账时这条链是通的：借走一个减一，还回来加一。
 * 没有台账的柜子仍按旧行为放行（真机接入前设备不上报，这里不强求），那条路由
 * {@code PowerbankTracker.Pick#tracked} 表达。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailableCountFollowsRentTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    final List<String> cabinets = new ArrayList<>();

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        jdbc.update("DELETE FROM dev_powerbank WHERE sn LIKE 'PBAC%'");
    }

    @Test
    @DisplayName("借走一个可借数减一，还回来加回去")
    void borrowAndReturnMoveTheNumber() {
        String cab = cabinetWithBanks(3);
        String token = consumerToken();

        assertThat(available(cab)).as("前置：三个宝在柜里").isEqualTo(3);

        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token)
                .okData().path("orderNo").asText();
        assertThat(available(cab)).as("借走一个就少一个 —— 此前恒等于种子里那个数")
                .isEqualTo(2);
        assertThat(borrowOnCard(cab)).as("门店卡读的就是这个数").isEqualTo(2);

        post("/internal/trade/orders/" + orderNo + "/return", Map.of("returnCabinetNo", cab), login("ADMIN")).okData();
        assertThat(available(cab)).as("还回来要加回去，否则一天下来柜子会「越借越空」").isEqualTo(3);
    }

    // —— 夹具 ——

    private int available(String cabinetNo) {
        Integer n = jdbc.queryForObject("SELECT available_count FROM dev_cabinet WHERE cabinet_no=?", Integer.class, cabinetNo);
        return n == null ? -1 : n;
    }

    /** 门店卡的可借数（C 端读的那条路）—— 与库里的列是同一个事实，分开读才能发现两者分叉。 */
    private int borrowOnCard(String cabinetNo) {
        String site = jdbc.queryForObject("SELECT site_no FROM dev_cabinet WHERE cabinet_no=?", String.class, cabinetNo);
        return get("/mp/sites/" + site, null).okData().path("availableBorrow").asInt();
    }

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    /** 一个独立站点 + 一台在线柜 + n 个在柜的宝（有台账）。 */
    private String cabinetWithBanks(int n) {
        String site = "STAC" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "可借数测试站点 " + site);
        String cab = "CBAC" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total,"
                + " available_count, status, online_status, last_heartbeat_at)"
                + " VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, ?, 'DEPLOYED', 'ONLINE', NOW(3))", cab, cab, site, n);
        for (int i = 1; i <= n; i++) {
            String pb = "PBAC" + rnd();
            jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, battery, status, cabinet_no, slot_index)"
                    + " VALUES (?, 'MAIN', ?, 90, 'IN_CABINET', ?, ?)", pb, pb, cab, i);
        }
        return cab;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
