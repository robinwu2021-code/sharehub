package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接入工单 §5.8 第三批（#8 #11 #12）：出参缺字段与列表缺筛选。
 *
 * <p>这一批的共同点是**前端能凑合但凑合得不对**：缺字段就多绕一次查询（还未必等价），
 * 缺筛选就整页拉回来在前端过一遍（总数偏大、翻页会漏）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpsFlowGaps2Test extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String admin;

    @BeforeAll
    void setUp() {
        admin = login("ADMIN");
    }

    @Test
    @DisplayName("#12 ★ 摘要卡的数字与按它筛出来的条数必须相等（对不上比卡片不可点更糟）")
    void preset_filter_matches_the_summary_card() {
        /*
         * 两个数都摆在界面上：卡片一个、列表标题一个。不相等的话人只会以为数据错了，
         * 而真正的原因是「摘要与列表各写了一遍条件」。服务端用同一段条件算两者，本条钉住它。
         *
         * ⚠️ **自己造一条「今日自愈」**：共享测试库里这个数长期是 0，
         * 不造的话这条断言是 0 == 0 —— 看着绿，其实什么也没验。
         */
        String no = "ALMGAP" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        jdbc.update("INSERT INTO dev_alarm (alarm_no, tenant_id, alarm_code, domain, status, level, "
                + "close_reason, closed_at, occurred_at) VALUES (?, 'MAIN', 'SITE_UNRENTABLE', 'AVAILABILITY', "
                + "'CLOSED', 'WARN', 'SELF_HEALED', NOW(3), NOW(3))", no);
        /*
         * 再造一条 **ACKED 且已处置**的。
         * 「已处置未关闭」的口径是 status IN (OPEN, ACKED)：库里恰好没有 ACKED 的这类行，
         * 于是把口径写成「只算 OPEN」时结果一模一样 —— 负对照跟着一起假绿（实测撞到过）。
         * 有了这一条，漏掉 ACKED 就会差 1，口径漂移才看得见。
         */
        String acked = "ALMACK" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        jdbc.update("INSERT INTO dev_alarm (alarm_no, tenant_id, alarm_code, domain, status, level, "
                + "disposition_ref, occurred_at) VALUES (?, 'MAIN', 'SITE_UNRENTABLE', 'AVAILABILITY', "
                + "'ACKED', 'WARN', 'WO-FAKE-FOR-TEST', NOW(3))", acked);
        try {
            JsonNode summary = get("/api/ops/alarms/summary", admin).okData();
            long disposedOpen = summary.path("disposedOpen").asLong();
            // 字段名以 AlarmSummary record 为准（autoRecoveredToday）——
            // 猜错名字时 path() 返回缺失节点、asLong() 给 0，断言会假绿，所以这里显式断言它存在
            assertThat(summary.has("autoRecoveredToday")).as("摘要出参字段名变了？%s", summary).isTrue();
            long healedToday = summary.path("autoRecoveredToday").asLong();
            assertThat(healedToday).as("刚造了一条，今日自愈不该还是 0 —— 否则下面那条断言是 0==0").isPositive();
            assertThat(disposedOpen).as("已处置未关闭为 0 的话这条也验不到东西").isPositive();

            assertThat(total("/api/ops/alarms/records?preset=DISPOSED_OPEN&page=1&size=1"))
                    .as("已处置未关闭：卡片 %s", disposedOpen).isEqualTo(disposedOpen);
            assertThat(total("/api/ops/alarms/records?preset=HEALED_TODAY&page=1&size=1"))
                    .as("今日自愈：卡片 %s", healedToday).isEqualTo(healedToday);
        } finally {
            jdbc.update("DELETE FROM dev_alarm WHERE alarm_no IN (?, ?)", no, acked);
        }
    }

    @Test
    @DisplayName("#12 拼错的 preset 忽略而不是 500——它来自 URL，错一个词不该让整页崩")
    void unknown_preset_is_ignored() {
        long all = total("/api/ops/alarms/records?page=1&size=1");
        Resp r = get("/api/ops/alarms/records?preset=NO_SUCH_PRESET&page=1&size=1", admin);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body.path("data").path("total").asLong()).isEqualTo(all);
    }

    @Test
    @DisplayName("#11 机柜与充电宝出参带上质检状态与所在仓（详情页此前得另查一次最近质检记录）")
    void qc_status_and_warehouse_are_in_the_payload() {
        JsonNode cab = firstRow("/api/ops/cabinets?page=1&size=1");
        if (cab != null) {
            assertThat(cab.has("qcStatus")).as("机柜出参应含 qcStatus").isTrue();
            assertThat(cab.has("warehouseNo")).as("机柜出参应含 warehouseNo").isTrue();
        }
        JsonNode pb = firstRow("/api/ops/powerbanks?page=1&size=1");
        if (pb != null) {
            assertThat(pb.has("qcStatus")).as("充电宝出参应含 qcStatus").isTrue();
            assertThat(pb.has("warehouseNo")).as("充电宝出参应含 warehouseNo").isTrue();
        }
        assertThat(cab != null || pb != null).as("库里至少要有一台设备，否则这条测的是空气").isTrue();
    }

    @Test
    @DisplayName("#8 调整项带账期，并支持按类型 / 来源 / 账期筛（按月的补差不带账期就分不清是哪个月）")
    void adjustment_has_period_and_filters() {
        Resp all = get("/api/ops/settlement-adjustments?page=1&size=1", admin);
        assertThat(all.status).isEqualTo(200);
        JsonNode row = all.body.path("data").path("list").path(0);
        if (!row.isMissingNode() && !row.isNull() && row.has("adjNo")) {
            assertThat(row.has("period")).as("调整项出参应含 period").isTrue();
        }
        // 三个筛选参数都要被认（不认的话是静默忽略：没报错也没生效，最难发现的那一类）
        for (String q : new String[]{"kind=GUARANTEE_TOPUP", "source=GUARANTEE", "period=2026-09"}) {
            Resp r = get("/api/ops/settlement-adjustments?page=1&size=1&" + q, admin);
            assertThat(r.status).as("%s → %s", q, r.body).isEqualTo(200);
            assertThat(r.body.path("data").path("total").asLong())
                    .as("%s 筛出来的不该多于全部", q).isLessThanOrEqualTo(all.body.path("data").path("total").asLong());
        }
        // 拼错的枚举当场 400，而不是静默返回空列表（「筛了个空」和「这个值不存在」是两回事）
        assertThat(get("/api/ops/settlement-adjustments?page=1&size=1&kind=NOPE", admin).status).isEqualTo(400);
    }

    @Test
    @DisplayName("#5 代理名下资产汇总不再恒空——此前端点、权限、前端调用都齐备，方法却什么也没查")
    void agent_assignments_are_no_longer_empty() {
        JsonNode r = get("/api/agent/assignments?page=1&size=5", admin).okData();
        assertThat(r.path("total").asLong()).as("库里有代理，汇总不该是 0 条").isPositive();

        JsonNode row = r.path("list").get(0);
        assertThat(row.has("cabinetCount") && row.has("siteCount")).as("%s", row).isTrue();

        /*
         * 与库直接对一遍：只断言「不为空」的话，哪天口径写错（比如把归档的也算进来）仍然是绿的。
         * 口径 = agent_no 直接挂在站点 / 机柜上，且未归档未软删。
         */
        String ag = row.path("agentNo").asText();
        Integer cabs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dev_cabinet WHERE agent_no=? AND deleted=0 AND archived_at IS NULL", Integer.class, ag);
        Integer sites = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loc_site WHERE agent_no=? AND deleted=0", Integer.class, ag);
        assertThat(row.path("cabinetCount").asInt()).as("代理 %s 的机柜数", ag).isEqualTo(cabs);
        assertThat(row.path("siteCount").asInt()).as("代理 %s 的站点数", ag).isEqualTo(sites);
    }

    @Test
    @DisplayName("#4 划拨候选统一成 PageResult + page/size，并认 excludeAgentNo")
    void assignable_assets_use_the_standard_page_shape() {
        Resp r = get("/api/agent/assignable-assets?page=1&size=3", admin);
        assertThat(r.status).isEqualTo(200);
        JsonNode d = r.okData();
        assertThat(d.has("list") && d.has("total")).as("全站统一形状：%s", d).isTrue();
        assertThat(d.path("list").size()).as("size=3 应当最多回 3 条").isLessThanOrEqualTo(3);

        long total = d.path("total").asLong();
        if (total == 0) return;   // 库里没有可划拨资产时后面几条无从验起

        /*
         * total 必须与**实际能翻到的**一致：候选池是选项源（上限 500 条），
         * 返回全表 count 会出现「总数 800 却翻到第 6 页就空了」—— 那种 total 是在骗人。
         */
        long lastPage = (total + 2) / 3;
        assertThat(get("/api/agent/assignable-assets?page=" + lastPage + "&size=3", admin).okData().path("list").size())
                .as("最后一页不该是空的（total=%s）", total).isPositive();

        // excludeAgentNo：后端自己认，不再靠前端过滤
        JsonNode owned = null;
        for (JsonNode x : get("/api/agent/assignable-assets?page=1&size=200", admin).okData().path("list")) {
            if (!x.path("currentAgentNo").isNull() && !x.path("currentAgentNo").asText().isBlank()) { owned = x; break; }
        }
        if (owned != null) {
            String ag = owned.path("currentAgentNo").asText();
            for (JsonNode x : get("/api/agent/assignable-assets?page=1&size=200&excludeAgentNo=" + ag, admin).okData().path("list")) {
                assertThat(x.path("currentAgentNo").asText("")).as("排除了 %s 就不该再出现它的资产", ag).isNotEqualTo(ag);
            }
        }
    }

    private long total(String path) {
        return get(path, admin).okData().path("total").asLong();
    }

    private JsonNode firstRow(String path) {
        JsonNode list = get(path, admin).okData().path("list");
        return list.isArray() && !list.isEmpty() ? list.get(0) : null;
    }
}
