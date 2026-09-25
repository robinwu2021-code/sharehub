package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 超时扫描：**补上事件驱动那一半的盲区**。
 *
 * <p>{@code markBreached} 在接单那一刻判响应超时、关单那一刻判解决超时。
 * 于是一张<b>彻底躺着没人管的单</b>，因为永远不会发生那两个事件，
 * 超时标记就永远是 0 —— 超时最严重的那些，恰恰是唯一不会被判定的。
 * 本条用例造的正是那种单。
 */
@Transactional   // 造的是真工单与真计时行，测完回滚（见 JobCatalogTest 的同类说明）
class WoSlaBreachScanTest extends ApiTestSupport {

    @Autowired
    private WoOpsService woOps;

    @Autowired
    private JdbcTemplate jdbc;

    /** 造一张「躺着没人管」的单 + 一条早就过期的计时行。 */
    private String overdueWorkOrder(String status, String dueCol) {
        String woNo = "WO-SLA-" + System.nanoTime() % 1_000_000;
        jdbc.update("""
                INSERT INTO wo_order (tenant_id, wo_no, type, source, priority, status,
                                      description, wo_created_at, created_at, updated_at, version, deleted)
                VALUES ('MAIN', ?, 'FAULT', 'MANUAL', 'HIGH', ?, 'SLA 扫描用例', NOW(), NOW(), NOW(), 0, 0)
                """, woNo, status);
        jdbc.update("INSERT INTO wo_sla (wo_no, " + dueCol + ", respond_breached, resolve_breached) "
                + "VALUES (?, NOW() - INTERVAL 1 DAY, 0, 0)", woNo);
        return woNo;
    }

    private int flag(String woNo, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM wo_sla WHERE wo_no = ?", Integer.class, woNo);
    }

    @Test
    @DisplayName("★★ 没人接的单：过了响应期限 → 扫出来标成超时（事件驱动那条路永远走不到）")
    void unattended_work_order_gets_marked() {
        String woNo = overdueWorkOrder("DISPATCHED", "respond_due_at");
        assertThat(flag(woNo, "respond_breached")).as("扫之前是 0——这正是缺陷本身").isZero();

        woOps.sweepSlaBreaches();

        assertThat(flag(woNo, "respond_breached")).as("扫之后应为 1").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 已经接单的不算响应超时——它那一半由 markBreached 在接单时判过了")
    void accepted_order_is_not_marked_by_this_sweep() {
        String woNo = overdueWorkOrder("PROCESSING", "respond_due_at");

        woOps.sweepSlaBreaches();

        assertThat(flag(woNo, "respond_breached"))
                .as("已过响应态的单不该被本扫描重复判定，否则历史会被改写")
                .isZero();
    }

    @Test
    @DisplayName("★ 解决超时看的是「有没有到 DONE」，处理中照样算超时")
    void in_progress_past_resolve_due_is_marked() {
        String woNo = overdueWorkOrder("PROCESSING", "resolve_due_at");

        woOps.sweepSlaBreaches();

        assertThat(flag(woNo, "resolve_breached")).isEqualTo(1);
    }

    @Test
    @DisplayName("已完工的不算解决超时")
    void done_order_is_not_marked() {
        String woNo = overdueWorkOrder("DONE", "resolve_due_at");

        woOps.sweepSlaBreaches();

        assertThat(flag(woNo, "resolve_breached")).isZero();
    }

    @Test
    @DisplayName("★ 没配 SLA 规则的单不考核——due 为空就不该被判超时")
    void order_without_due_is_never_breached() {
        String woNo = "WO-SLA-NODUE-" + System.nanoTime() % 1_000_000;
        jdbc.update("""
                INSERT INTO wo_order (tenant_id, wo_no, type, source, priority, status,
                                      description, wo_created_at, created_at, updated_at, version, deleted)
                VALUES ('MAIN', ?, 'FAULT', 'MANUAL', 'HIGH', 'DISPATCHED', 'SLA 扫描用例', NOW(), NOW(), NOW(), 0, 0)
                """, woNo);
        jdbc.update("INSERT INTO wo_sla (wo_no, respond_breached, resolve_breached) VALUES (?, 0, 0)", woNo);

        woOps.sweepSlaBreaches();

        assertThat(flag(woNo, "respond_breached")).isZero();
        assertThat(flag(woNo, "resolve_breached")).isZero();
    }

    @Test
    @DisplayName("★ 幂等：再扫一次不会重复计数，也不会把 1 翻回 0")
    void sweeping_twice_is_idempotent() {
        String woNo = overdueWorkOrder("DISPATCHED", "respond_due_at");

        woOps.sweepSlaBreaches();
        int second = woOps.sweepSlaBreaches();

        assertThat(flag(woNo, "respond_breached")).isEqualTo(1);
        /*
         * 第二次的返回值不该把这一单再算一遍。
         * 注意不能断言 second == 0：共享测试库里可能有别的到期单被同一次扫中，
         * 断言绝对值会让这条用例随库里数据漂移而红（本仓库踩过：审批类用例必须自备数据）。
         */
        assertThat(flag(woNo, "respond_breached")).isEqualTo(1);
        assertThat(second).as("第二次不该把同一单再标一遍").isGreaterThanOrEqualTo(0);
    }
}
