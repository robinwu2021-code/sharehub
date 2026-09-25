package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import ai.neargo.sharehub.trade.service.RentOrderService;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AlarmDraft;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 执行清单批次 A：借还驱动充电宝状态（A2）· 逾期达封顶转买断（A1）· 无责任人派区域运维（A3）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderAssetLinkTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RentOrderService rents;
    @Autowired
    WoOpsService workOrders;

    final List<String> cabinets = new ArrayList<>();
    final List<String> employees = new ArrayList<>();
    final List<String> powerbanks = new ArrayList<>();

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        // 买断后的宝已不挂在柜上（SOLD），按柜删不到 —— 按宝号删
        for (String pb : powerbanks) jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?", pb);
        for (String e : employees) {
            jdbc.update("DELETE FROM iam_data_scope WHERE subject_type='EMPLOYEE' AND subject_no=?", e);
            jdbc.update("DELETE FROM iam_employee WHERE employee_no=?", e);
        }
    }

    @Test
    @DisplayName("A2 借出挑宝：跳过锁仓与低电量，取电量最高的一颗 → RENTED；归还 → 回柜 IN_CABINET")
    void rentPicksAndReturnPutsBack() {
        String cab = liveCabinet();
        String low = powerbank(cab, 1, 40);
        String good = powerbank(cab, 2, 90);
        String locked = powerbank(cab, 3, 100);
        jdbc.update("INSERT INTO dev_protection (protection_no, tenant_id, cabinet_no, slot_index, action, holder_type, holder_ref, reason, active)"
                + " VALUES (?, 'MAIN', ?, 3, 'SLOT_LOCK', 'MANUAL', 'TEST', '测试锁仓', 1)", "PRTA2" + rnd(), cab);

        String orderNo = rents.rent("CU-A2-" + rnd(), cab, true, null).orderNo();
        assertThat(jdbc.queryForObject("SELECT powerbank_no FROM ord_order WHERE order_no=?", String.class, orderNo)).isEqualTo(good);
        assertThat(status(good)).isEqualTo("RENTED");
        assertThat(status(low)).isEqualTo("IN_CABINET");
        assertThat(status(locked)).isEqualTo("IN_CABINET");

        String other = liveCabinet();
        rents.returnOrder(orderNo, other);
        assertThat(jdbc.queryForMap("SELECT status, cabinet_no FROM dev_powerbank WHERE powerbank_no=?", good))
                .containsEntry("status", "IN_CABINET").containsEntry("cabinet_no", other);
    }

    @Test
    @DisplayName("A2 有宝台账但没有一颗能借（都低电量）→ 409「暂无可借充电宝」")
    void noRentableRejects() {
        String cab = liveCabinet();
        powerbank(cab, 1, 20);
        assertThatThrownBy(() -> rents.rent("CU-A2-" + rnd(), cab, true, null)).hasMessage("error.rent.no_stock");
    }

    @Test
    @DisplayName("A1 逾期达封顶：进行中 5 天、买断价 100 → 买断结单（金额 100、买断标记）、宝 → SOLD、发结算事件；未触顶的不动")
    void overdueBuyout() {
        String cab = liveCabinet();
        String pb = powerbank(cab, 1, 90);
        jdbc.update("UPDATE dev_powerbank SET status='RENTED', cabinet_no=NULL, slot_index=NULL WHERE powerbank_no=?", pb);
        // 快照不带日封顶（按 30 分钟 3 元累计，5 天远超 100），总封顶 = 买断价 100
        String snapshot = "{\"planNo\":\"PP-TEST\",\"currency\":\"AED\",\"items\":[{\"itemType\":\"TIME_FEE\",\"metering\":\"MINUTE\","
                + "\"freeQty\":0,\"unitQty\":30,\"rounding\":\"CEIL\",\"ladders\":[{\"fromQty\":0,\"toQty\":null,\"unitPrice\":3}],\"capDaily\":null}],"
                + "\"capTotal\":\"100\"}";
        String capped = order(cab, pb, snapshot, 5 * 24);
        String young = order(cab, null, snapshot, 2);

        assertThat(rents.buyoutIfCapped(capped)).isTrue();
        assertThat(rents.buyoutIfCapped(young)).as("2 小时 = 12 元，未触顶").isFalse();
        assertThat(jdbc.queryForMap("SELECT status, buyout, amount FROM ord_order WHERE order_no=?", capped))
                .containsEntry("status", "SETTLED").containsEntry("buyout", true);
        assertThat(jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no=?", java.math.BigDecimal.class, capped))
                .isEqualByComparingTo("100");
        assertThat(status(pb)).isEqualTo("SOLD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_type='ORDER_SETTLED' AND aggregate_id=?",
                Integer.class, capped)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, young)).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("A3 站点无运维责任人 → 派给数据范围覆盖该区域、在手工单最少的 OPS 员工（策略 LOAD）")
    void regionOperatorDispatch() {
        String region = "R-A3-" + rnd();
        String busy = employee(region), idle = employee(region);
        String site = site(region);
        // busy 先占一张未完结的单
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, status, assignee_name) VALUES (?, 'MAIN', 'FAULT', 'MANUAL', 'DISPATCHED', ?)",
                "WOA3" + rnd(), busy);
        String woNo = workOrders.openOrAttach(new AlarmDraft("FAULT", "HIGH", "ALM:A3:" + rnd(), null, site, "区域运维派单测试", "A3"));
        assertThat(jdbc.queryForMap("SELECT status, assignee_name, dispatch_strategy FROM wo_order WHERE wo_no=?", woNo))
                .containsEntry("status", "DISPATCHED").containsEntry("assignee_name", idle).containsEntry("dispatch_strategy", "LOAD");
    }

    // —— 夹具 ——

    private String liveCabinet() {
        String site = site("R-TEST");
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), ?, 0.2, ?, ?, 'ACTIVE')",
                "CTA2" + rnd(), site, site, LocalDate.now().minusDays(1).toString(), LocalDate.now().plusYears(1).toString());
        String cab = "CBO" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count, status,"
                + " online_status, last_heartbeat_at) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 3, 'DEPLOYED', 'ONLINE', NOW(3))", cab, cab, site);
        return cab;
    }

    private String site(String region) {
        String site = "STO" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id) VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', ?)",
                site, venue, "批次A测试站点 " + site, region);
        return site;
    }

    private String powerbank(String cab, int slot, int battery) {
        String pb = "PBO" + rnd();
        powerbanks.add(pb);
        jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, battery, status, cabinet_no, slot_index) VALUES (?, 'MAIN', ?, ?, 'IN_CABINET', ?, ?)",
                pb, pb, battery, cab, slot);
        return pb;
    }

    private String employee(String region) {
        String no = "EA3" + rnd();
        employees.add(no);
        jdbc.update("INSERT INTO iam_employee (employee_no, tenant_id, name, role_no, status) VALUES (?, 'MAIN', ?, 'OPS', 'ACTIVE')", no, "区域运维 " + no);
        jdbc.update("INSERT INTO iam_data_scope (subject_type, subject_no, scope_type, scope_refs) VALUES ('EMPLOYEE', ?, 'REGION', ?)",
                no, "[\"" + region + "\"]");
        return no;
    }

    private String order(String cab, String pb, String snapshot, int hoursAgo) {
        String no = "ORDA1" + rnd();
        LocalDateTime startUtc = LocalDateTime.now(ZoneOffset.UTC).minusHours(hoursAgo);
        jdbc.update("INSERT INTO ord_order (order_no, tenant_id, c_user_no, cabinet_no, site_no, status, device_type, powerbank_no, currency,"
                        + " amount, started_at, rent_start_at, price_snapshot) VALUES (?, 'MAIN', 'CU-A1', ?, (SELECT site_no FROM dev_cabinet WHERE cabinet_no=?),"
                        + " 'IN_USE', 'POWERBANK', ?, 'AED', 0, ?, ?, ?)",
                no, cab, cab, pb, LocalDateTime.now().minusHours(hoursAgo), startUtc.toString(), snapshot);
        return no;
    }

    private String status(String pb) {
        return jdbc.queryForObject("SELECT status FROM dev_powerbank WHERE powerbank_no=?", String.class, pb);
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
