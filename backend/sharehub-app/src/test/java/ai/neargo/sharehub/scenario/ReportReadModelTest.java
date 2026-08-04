package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报表域读模型验收（{@code /api/ops/reports/**}）。
 *
 * <p>本测试**刻意不断言任何硬编码数字**。报表是聚合读模型，行数与金额随库里数据变；
 * 钉住具体数字的测试第一次改种子就会红，然后被人顺手改成新数字 —— 断言就此失去意义。
 * 这里钉的是三类**不变量**，它们才是报表唯一会真正出错的地方：
 * <ol>
 *   <li><b>周期口径</b>：周期报表统计到<b>昨日</b>（T+1 跑批），大屏统计<b>今日到现在</b>。
 *       这两条一旦被后来者「顺手统一」，同一屏就会出现两个不同的「今日」。</li>
 *   <li><b>合计 = 各部分之和</b>：表格 Σ = 折线 Σ = 汇总条；大屏分时 Σ = KPI；画像 Σ = 总人数。
 *       对不上意味着某处偷偷另算了一遍口径。</li>
 *   <li><b>权限码</b>：OPS 只持有 {@code report:device:read} / {@code report:location:read}，
 *       财务/大屏/自定义/消费者对它必须 403 —— 营收数据不进运维视角。</li>
 * </ol>
 */
class ReportReadModelTest extends ApiTestSupport {

    /** 取全量而非默认 10 条：合计断言必须覆盖所有行，分页一截就永远对不上。 */
    private static final String ALL = "page=1&size=500";

    /** 断言用周期取 LAST_12M（360 天）：窗口足够宽，不会因为种子数据变旧而变成全 0 空断言。 */
    private static final String WIDE = "period=LAST_12M";

    // ————————————————————————————————————————————————————————————
    // ① 周期口径：周期报表到昨日，大屏到今日现在
    // ————————————————————————————————————————————————————————————

    @Test
    void period_report_stops_at_yesterday_not_today() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        JsonNode rows = admin("/api/ops/reports/finance?period=LAST_7D&" + ALL).path("list");

        assertThat(rows).as("LAST_7D 应切成 7 个日桶（天数取桶宽整数倍，无残桶）").hasSize(7);

        List<String> labels = new ArrayList<>();
        rows.forEach(r -> labels.add(r.path("period").asText()));
        assertThat(labels.get(6)).as("最后一个桶必须是昨日（T+1 跑批口径）")
                .isEqualTo(today.minusDays(1).toString());
        assertThat(labels.get(0)).as("第一个桶 = 昨日往前推 6 天")
                .isEqualTo(today.minusDays(7).toString());
        assertThat(labels).as("周期报表**不得**出现今日 —— 今日是残日，与整日并排看会被当成掉量")
                .doesNotContain(today.toString());
    }

    @Test
    void screen_board_covers_today_up_to_current_hour() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int nowHour = java.time.Instant.now().atZone(ZoneOffset.UTC).getHour();
        JsonNode board = admin("/api/ops/reports/screen-board");

        assertThat(board.path("updatedAt").asText()).as("大屏时间戳是「现在」，不是昨日")
                .startsWith(today.toString());
        assertThat(board.path("today")).as("分时序列 = 今日 00:00 到当前小时（含）")
                .hasSize(nowHour + 1);
        assertThat(board.path("today").get(0).path("hour").asText()).isEqualTo("00:00");
        assertThat(board.path("today").get(nowHour).path("hour").asText())
                .isEqualTo(String.format("%02d:00", nowHour));
    }

    @Test
    void illegal_period_falls_back_to_default_instead_of_400() {
        // period 从 URL 来，旧书签带着过期枚举时报表页应正常显示缺省周期（LAST_30D = 30 个日桶）。
        JsonNode rows = admin("/api/ops/reports/finance?period=LAST_42Y&" + ALL).path("list");
        assertThat(rows).as("非法周期落回缺省 LAST_30D").hasSize(30);
    }

    // ————————————————————————————————————————————————————————————
    // ② 合计 = 各部分之和
    // ————————————————————————————————————————————————————————————

    @Test
    void finance_table_sum_equals_trend_sum_equals_summary() {
        JsonNode table = admin("/api/ops/reports/finance?" + WIDE + "&" + ALL).path("list");
        JsonNode trend = admin("/api/ops/reports/trend?kind=FINANCE&" + WIDE);

        double tableGmv = sum(table, "gmv");
        double pointsGmv = sum(trend.path("points"), "revenue");
        double summaryGmv = summary(trend, "GMV");

        assertThat(tableGmv).as("前置：宽周期内应有订单事实，否则本断言退化成 0 == 0").isGreaterThan(0d);
        assertThat(pointsGmv).as("折线 Σ 必须等于表格 Σ（同一份事实的两种折法）")
                .isCloseTo(tableGmv, org.assertj.core.api.Assertions.within(0.01));
        assertThat(summaryGmv).as("汇总条必须等于折线 Σ")
                .isCloseTo(pointsGmv, org.assertj.core.api.Assertions.within(0.01));
        assertThat(table).as("财务报表一行 = 折线一个点，行数必须相同").hasSize(trend.path("points").size());

        // 净收入 = GMV − 分润，逐桶成立 → 全周期也成立。opex 无表故成本只有分润一项（见 impl 注释）。
        assertThat(summaryGmv - summary(trend, "分润"))
                .as("净收入 = GMV − 分润")
                .isCloseTo(summary(trend, "净收入"), org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    void device_table_orders_sum_equals_trend_summary() {
        JsonNode table = admin("/api/ops/reports/device?" + WIDE + "&" + ALL).path("list");
        JsonNode trend = admin("/api/ops/reports/trend?kind=DEVICE&" + WIDE);

        double tableOrders = sum(table, "orders");
        assertThat(tableOrders).as("前置：宽周期内应有订单").isGreaterThan(0d);
        assertThat(sum(trend.path("points"), "orders"))
                .as("按桶折 Σ = 按站点折 Σ")
                .isCloseTo(tableOrders, org.assertj.core.api.Assertions.within(0.5));
        assertThat(summary(trend, "周期订单"))
                .as("汇总条「周期订单」= 表格 Σ")
                .isCloseTo(tableOrders, org.assertj.core.api.Assertions.within(0.5));
    }

    @Test
    void location_table_revenue_sum_equals_finance_gmv() {
        // 点位报表（按站点折）与财务报表（按桶折）是同一份事实，营收必须一分不差。
        double byLocation = sum(admin("/api/ops/reports/location?" + WIDE + "&" + ALL).path("list"), "revenue");
        double byBucket = sum(admin("/api/ops/reports/finance?" + WIDE + "&" + ALL).path("list"), "gmv");
        assertThat(byLocation).isGreaterThan(0d);
        assertThat(byLocation).as("点位报表 Σrevenue = 财务报表 Σgmv")
                .isCloseTo(byBucket, org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    void screen_board_kpis_equal_their_own_series() {
        JsonNode board = admin("/api/ops/reports/screen-board");

        double kpiGmv = kpi(board, "今日GMV");
        double kpiOrders = kpi(board, "今日订单");
        assertThat(sum(board.path("today"), "gmv"))
                .as("KPI 今日 GMV 必须等于它下面那条曲线的 Σ —— 对不上是最刺眼的假")
                .isCloseTo(kpiGmv, org.assertj.core.api.Assertions.within(0.01));
        assertThat(sum(board.path("today"), "orders"))
                .isCloseTo(kpiOrders, org.assertj.core.api.Assertions.within(0.5));
        assertThat(sum(board.path("ranking"), "gmv"))
                .as("站点排名 Σgmv 必须等于今日 GMV（全站点不截断）")
                .isCloseTo(kpiGmv, org.assertj.core.api.Assertions.within(0.01));

        // 柜机构成三片互斥且穷尽 → Σ = 机柜总数；在线率 = 在线 / 总数 × 100 用同一分母。
        double total = sum(board.path("cabinetStatus"), "value");
        assertThat(total).as("前置：库里应有机柜").isGreaterThan(0d);
        double online = kpi(board, "在线柜机");
        assertThat(kpi(board, "在线率"))
                .as("在线率与在线柜机必须共用「机柜总数」这一个分母")
                .isCloseTo(Math.round(online / total * 100 * 100) / 100d,
                        org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    void consumer_profiles_sum_equals_total_users_equals_segment_users() {
        JsonNode insight = admin("/api/ops/reports/consumer-insight");
        long total = insight.path("totalUsers").asLong();
        assertThat(total).as("前置：应有成功借出过的用户").isGreaterThan(0L);

        assertThat(sum(insight.path("profiles"), "value"))
                .as("同一维度（PERIOD）画像 Σvalue = totalUsers —— 饼图分母")
                .isCloseTo(total, org.assertj.core.api.Assertions.within(0.5));

        double segUsers = sum(admin("/api/ops/reports/consumer-segments?" + ALL).path("list"), "userCount");
        assertThat(segUsers).as("人群分层 Σ 用户数 = totalUsers（表与图必须是同一批人）")
                .isCloseTo(total, org.assertj.core.api.Assertions.within(0.5));

        // 漏斗必须单调不增：下游人数大于上游意味着某个环节的口径取错了。
        long prev = Long.MAX_VALUE;
        for (JsonNode s : insight.path("funnel")) {
            long users = s.path("users").asLong();
            assertThat(users).as("漏斗环节「%s」不得多于上游", s.path("stage").asText()).isLessThanOrEqualTo(prev);
            prev = users;
        }
        assertThat(insight.path("funnel").get(1).path("users").asLong())
                .as("漏斗「成功借出」= totalUsers（同一批人）").isEqualTo(total);
    }

    @Test
    void custom_report_gmv_by_site_equals_gmv_by_scene() {
        // 自定义报表换维度只是换折法，GMV 总量不该随维度变。
        double bySite = sumMetric(admin("/api/ops/reports/custom?dim=SITE&metrics=GMV&" + WIDE + "&" + ALL));
        double byScene = sumMetric(admin("/api/ops/reports/custom?dim=SCENE&metrics=GMV&" + WIDE + "&" + ALL));
        double byMonth = sumMetric(admin("/api/ops/reports/custom?dim=MONTH&metrics=GMV&" + WIDE + "&" + ALL));
        assertThat(bySite).isGreaterThan(0d);
        assertThat(byScene).as("按场景折 Σ = 按站点折 Σ")
                .isCloseTo(bySite, org.assertj.core.api.Assertions.within(0.01));
        assertThat(byMonth).as("按周期桶折 Σ = 按站点折 Σ")
                .isCloseTo(bySite, org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    void custom_report_drops_unknown_metrics_instead_of_500() {
        JsonNode rows = admin("/api/ops/reports/custom?dim=SITE&metrics=GMV,NOT_A_METRIC&" + ALL).path("list");
        rows.forEach(r -> assertThat(r.path("metric").asText())
                .as("目录外的指标 key 静默丢弃（前端勾选框同源，出现未知 key 只可能是手拼 URL）")
                .isEqualTo("GMV"));
    }

    @Test
    void metrics_catalogue_matches_frontend_contract() {
        JsonNode m = admin("/api/ops/reports/metrics");
        List<String> keys = new ArrayList<>();
        m.forEach(x -> keys.add(x.path("key").asText()));
        assertThat(keys).as("指标目录与 ops-web REPORT_METRICS 同序同名")
                .containsExactly("GMV", "ORDERS", "AOV", "COST", "NET", "ONLINE_RATE", "TURNOVER", "FAULT_RATE");
        m.forEach(x -> assertThat(x.path("format").asText())
                .as("format 决定前端渲染方式，不能缺").isIn("MONEY", "RATE", "NUMBER"));
    }

    // ————————————————————————————————————————————————————————————
    // ③ 权限码
    // ————————————————————————————————————————————————————————————

    @Test
    void anonymous_cannot_read_any_report() {
        assertThat(get("/api/ops/reports/device", null).status).isEqualTo(401);
        assertThat(get("/api/ops/reports/screen-board", null).status).isEqualTo(401);
    }

    @Test
    void ops_reads_device_and_location_but_not_revenue_reports() {
        String ops = login("OPS");
        // OPS 持有 report:device:read / report:location:read
        get("/api/ops/reports/device", ops).okData();
        get("/api/ops/reports/location", ops).okData();
        // report:* 归 FINANCE/BD/VIEWER —— 营收/大屏/自定义/消费者对 OPS 必须 403
        for (String path : List.of("/api/ops/reports/finance", "/api/ops/reports/screen",
                "/api/ops/reports/screen-board", "/api/ops/reports/custom",
                "/api/ops/reports/metrics", "/api/ops/reports/consumer-insight",
                "/api/ops/reports/consumer-segments")) {
            Resp r = get(path, ops);
            assertThat(r.status).as("OPS 访问 %s 应 403", path).isEqualTo(403);
            assertThat(r.code()).isEqualTo(403);
        }
    }

    @Test
    void trend_permission_follows_kind_not_endpoint() {
        String ops = login("OPS");
        // 同一个端点：设备口径放行、财务口径拒绝。若两者行为相同，说明动态权限码没生效 ——
        // 要么 OPS 看不到设备报表的曲线，要么 OPS 能从趋势端点拿到 GMV。
        get("/api/ops/reports/trend?kind=DEVICE", ops).okData();
        get("/api/ops/reports/trend?kind=LOCATION", ops).okData();
        assertThat(get("/api/ops/reports/trend?kind=FINANCE", ops).status).isEqualTo(403);
        // 缺省 kind = FINANCE，故不传也应拒绝（不能靠「不传参数」绕过财务权限）。
        assertThat(get("/api/ops/reports/trend", ops).status).isEqualTo(403);
    }

    @Test
    void finance_role_reads_all_reports() {
        String fin = login("FINANCE"); // 持 report:*
        get("/api/ops/reports/finance", fin).okData();
        get("/api/ops/reports/screen-board", fin).okData();
        get("/api/ops/reports/custom", fin).okData();
        get("/api/ops/reports/consumer-segments", fin).okData();
        get("/api/ops/reports/trend?kind=FINANCE", fin).okData();
    }

    // ————————————————————————————————————————————————————————————
    // ④ 站点坪效端点已由报表域接上（原为空实现 + TODO）
    // ————————————————————————————————————————————————————————————

    @Test
    void site_analysis_no_longer_returns_empty_page() {
        JsonNode rows = admin("/api/ops/site-analysis?" + ALL).path("list");
        assertThat(rows).as("坪效端点原是空实现（返回空页），现委托报表域站点卷积").isNotEmpty();
        rows.forEach(r -> assertThat(r.path("paybackDays").asInt())
                .as("回本天数仍不可得（缺资产成本表），恒 0 由前端渲染成「—」").isZero());
    }

    // —— helpers ——

    private JsonNode admin(String path) {
        return get(path, login("ADMIN")).okData();
    }

    private static double sum(JsonNode arr, String field) {
        double s = 0;
        for (JsonNode n : arr) {
            s += n.path(field).asDouble();
        }
        return s;
    }

    /** 自定义报表是长表（dim × metric × value），只有一个指标时直接求 value 之和。 */
    private static double sumMetric(JsonNode page) {
        return sum(page.path("list"), "value");
    }

    private static double summary(JsonNode trend, String label) {
        for (JsonNode item : trend.path("summary")) {
            if (label.equals(item.path("label").asText())) {
                return item.path("value").asDouble();
            }
        }
        throw new AssertionError("汇总条缺少「" + label + "」项：" + trend.path("summary"));
    }

    private static double kpi(JsonNode board, String metric) {
        for (JsonNode item : board.path("kpis")) {
            if (metric.equals(item.path("metric").asText())) {
                return item.path("value").asDouble();
            }
        }
        throw new AssertionError("大屏缺少 KPI「" + metric + "」：" + board.path("kpis"));
    }
}
