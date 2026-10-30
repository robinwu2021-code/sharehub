package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.RentFixture;
import ai.neargo.sharehub.trade.service.RentOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 5 批（运营方原始需求 §3.2 P0 + 小配置，2026-10-29）：
 * 订单筛选与导出（RNT-03）、进行中订单字段（MON-01）、支付渠道（PAY-01）、
 * 方案保存同步计费项与周期取整（C03，顺带修「界面改价不生效」）、分成比例区间（C11）。
 */
class Batch5OrderOpsTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RentOrderService rents;

    private String rentedOrder() {
        RentFixture.rentable(jdbc);
        String phone = "+97153" + (1_000_000 + (System.nanoTime() % 8_000_000));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        String token = post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
        String no = post("/mp/trade/orders/rent", Map.of("cabinetNo", RentFixture.CABINET, "useFreeDeposit", true), token)
                .okData().path("orderNo").asText();
        jdbc.update("UPDATE ord_order SET status = 'IN_USE', rent_start_at = rent_start_at - INTERVAL 3 HOUR, "
                + "price_snapshot = JSON_SET(COALESCE(price_snapshot, '{}'), '$.capTotal', '69.00') WHERE order_no = ?", no);
        return no;
    }

    private JsonNode find(String query, String orderNo) {
        for (JsonNode o : get("/api/trade/orders?size=200&" + query, login("ADMIN")).okData().path("list")) {
            if (o.path("orderNo").asText().equals(orderNo)) return o;
        }
        return null;
    }

    @Test
    @DisplayName("★★ 订单筛选：设备 / 日期 / 商家；进行中订单带实时时长、已累计费用、最高收费")
    void filters_and_live_fields() {
        String no = rentedOrder();
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dubai")).toString();

        JsonNode o = find("cabinetNo=" + RentFixture.CABINET + "&from=" + today + "&to=" + today, no);
        assertThat(o).as("按设备 + 今天能查到").isNotNull();
        assertThat(o.path("liveDurationMin").asInt()).isGreaterThanOrEqualTo(179);
        assertThat(o.path("accruedFee").isNull()).as("进行中要有已累计费用").isFalse();
        assertThat(o.path("maxCharge").decimalValue()).isEqualByComparingTo("69.00");

        assertThat(find("cabinetNo=NO-SUCH-CAB", no)).isNull();
        assertThat(find("from=2099-01-01", no)).as("借出日期之外").isNull();
        assertThat(find("agentNo=AG-NOBODY", no)).isNull();
        assertThat(get("/api/trade/orders?from=2026-13-40", login("ADMIN")).status).isEqualTo(400);
    }

    @Test
    @DisplayName("★ 请款记支付渠道（缺省 NEARPAY），订单列表带支付单号与渠道；后端导出含该单")
    void payment_channel_and_export() throws Exception {
        String no = rentedOrder();
        jdbc.update("UPDATE ord_order SET rent_start_at = rent_start_at - INTERVAL 50 HOUR WHERE order_no = ?", no);
        assertThat(rents.chargeOverdueIfDue(no, 48)).isTrue();
        assertThat(jdbc.queryForObject("SELECT channel_code FROM pay_order WHERE order_no = ? ORDER BY id DESC LIMIT 1",
                String.class, no)).isEqualTo("NEARPAY");
        JsonNode o = find("keyword=" + no, no);
        assertThat(o.path("payNo").asText()).isNotBlank();
        assertThat(o.path("payChannel").asText()).isEqualTo("NEARPAY");

        HttpResponse<byte[]> x = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/trade/orders/export?format=xlsx&keyword=" + no))
                .header("Authorization", "Bearer " + login("ADMIN")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(x.statusCode()).isEqualTo(200);
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(x.body()))) {
            String sheet = null;
            for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().equals("xl/worksheets/sheet1.xml")) sheet = new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertThat(sheet).contains(no).contains("NEARPAY");
        }
    }

    private Map<String, Object> plan(String no, String unitPrice, String rounding) {
        Map<String, Object> m = new HashMap<>();
        m.put("planNo", no);
        m.put("name", "Batch5 " + no);
        m.put("freeMinutes", 5);
        m.put("unitMinutes", 60);
        m.put("unitPrice", new BigDecimal(unitPrice));
        m.put("currency", "AED");
        if (rounding != null) m.put("unitRounding", rounding);
        return m;
    }

    @Test
    @DisplayName("★★ 界面保存方案即同步计时费用项（此前从不写，改价不生效）；周期取整可选，缺省取系统参数")
    void plan_save_syncs_time_item_and_rounding() {
        String admin = login("ADMIN");
        String no = post("/api/trade/price-plans", plan(null, "3.00", null), admin).okData().path("planNo").asText();
        Map<String, Object> item = jdbc.queryForMap("SELECT i.item_no, i.free_qty, i.unit_qty, i.rounding, l.unit_price "
                + "FROM price_plan_item i JOIN price_ladder l ON l.item_no = i.item_no WHERE i.plan_no = ?", no);
        assertThat(item.get("rounding")).as("缺省取 billing.unit_rounding").isEqualTo("CEIL");
        assertThat((BigDecimal) item.get("free_qty")).isEqualByComparingTo("5");
        assertThat((BigDecimal) item.get("unit_qty")).isEqualByComparingTo("60");
        assertThat((BigDecimal) item.get("unit_price")).isEqualByComparingTo("3.00");

        JsonNode upd = post("/api/trade/price-plans/" + no, plan(no, "4.50", "FLOOR"), admin).okData();
        assertThat(upd.path("unitRounding").asText()).isEqualTo("FLOOR");
        Map<String, Object> after = jdbc.queryForMap("SELECT i.rounding, l.unit_price, "
                + "(SELECT COUNT(*) FROM price_plan_item WHERE plan_no = ?) AS n "
                + "FROM price_plan_item i JOIN price_ladder l ON l.item_no = i.item_no WHERE i.plan_no = ?", no, no);
        assertThat(after.get("rounding")).isEqualTo("FLOOR");
        assertThat((BigDecimal) after.get("unit_price")).as("改价落到计费项").isEqualByComparingTo("4.50");
        assertThat(((Number) after.get("n")).intValue()).as("不重复建项").isEqualTo(1);

        assertThat(post("/api/trade/price-plans/" + no, plan(no, "4.50", "ROUND"), admin).status).isEqualTo(400);
    }

    @Test
    @DisplayName("分成规则比例越出 finance.share.rate_min / rate_max 拒绝保存；0 = 明确不分，放行")
    void share_rate_range() {
        // 缺省不约束（现行）；按运营方原文开 15%–50%
        jdbc.update("UPDATE sys_param SET value = '0.15' WHERE param_key = 'finance.share.rate_min'");
        jdbc.update("UPDATE sys_param SET value = '0.50' WHERE param_key = 'finance.share.rate_max'");
        try {
            checkRange();
        } finally {
            jdbc.update("UPDATE sys_param SET value = '0' WHERE param_key = 'finance.share.rate_min'");
            jdbc.update("UPDATE sys_param SET value = '1' WHERE param_key = 'finance.share.rate_max'");
        }
    }

    private void checkRange() {
        String admin = login("ADMIN");
        Map<String, Object> m = new HashMap<>(Map.of("dimension", "AGENT", "payeeNo", "AG-RANGE-" + System.nanoTime(),
                "payeeName", "range", "mode", "LEDGER", "priority", 5));
        m.put("rate", 0.05);
        assertThat(post("/api/trade/share-rules", m, admin).status).isEqualTo(400);
        m.put("rate", 0.6);
        assertThat(post("/api/trade/share-rules", m, admin).status).isEqualTo(400);
        m.put("rate", 0.3);
        assertThat(post("/api/trade/share-rules", m, admin).status).isEqualTo(200);
        m.put("rate", 0);
        assertThat(post("/api/trade/share-rules", m, admin).status).isEqualTo(200);
    }
}
