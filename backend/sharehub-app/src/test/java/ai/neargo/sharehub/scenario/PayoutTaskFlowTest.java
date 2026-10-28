package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 付款任务（V158；需求方案 §八 / D8 / D12，验收 AC-08 ~ AC-11 与 AC-08b）。
 *
 * <p>结算单用 2001 年的账期、商家用每次新造的编号，截止日选在 2001 年 —— 只会碰到本类自己造的数据，
 * 不会把共享测试库里别的用例的结算单纳进任务。
 *
 * <p>角色：管理员发起与确认、财务复核 —— 四眼（复核人 ≠ 发起人）与「审批人不兼放款人」都满足。
 */
class PayoutTaskFlowTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String merchantA = "AGPA" + tag;
    private final String merchantB = "AGPB" + tag;   // 没录收款账户
    private final String merchantC = "AGPC" + tag;
    private static final String IBAN_A = "AE070331234567890123456";

    /**
     * 本类造的结算单（STLP 前缀、2001 年账期）跑完一条就退出可付款范围。
     * 截止日只有上限：上一条用例没付掉的 2001 年结算单，会被下一条用例的任务一起纳入。
     */
    @org.junit.jupiter.api.BeforeEach
    void retireLeftovers() {
        jdbc.update("UPDATE stl_settlement SET status = 'PAID' WHERE settle_no LIKE 'STLP%' "
                + "AND status = 'CONFIRMED' AND withdraw_no IS NULL AND period < '2010'");
    }

    /** 用例之间不留未结束的任务 —— 「同一时刻至多一个」会挡住下一条用例。 */
    @AfterEach
    void closeOpenTasks() {
        jdbc.update("UPDATE stl_payout_task SET status = 'CANCELLED' WHERE status IN ('PENDING_REVIEW','PENDING_TRANSFER')");
    }

    private void settlement(String payee, String period, String amount) {
        jdbc.update("INSERT INTO stl_settlement (settle_no, tenant_id, payee_type, payee_no, payee_name, period, "
                        + "total_amount, currency, status) VALUES (?, 'MAIN', 'AGENT', ?, ?, ?, ?, 'AED', 'CONFIRMED')",
                "STLP" + tag + payee.substring(3, 4) + period.replace("-", ""), payee, "商家 " + payee, period, amount);
    }

    private void account(String payee, String iban) {
        post("/api/trade/payout-accounts", Map.of("payeeType", "AGENT", "payeeNo", payee, "bankCode", "BK001",
                "accountName", "Beneficiary " + payee, "accountMasked", iban, "currency", "AED"), login("ADMIN")).okData();
    }

    private String settleNo(String payee, String period) {
        return "STLP" + tag + payee.substring(3, 4) + period.replace("-", "");
    }

    @Test
    @DisplayName("★★ 截止日只纳入到期的结算单，一个商家合并一笔；缺收款账户的单列为不能付（AC-08b）")
    void preview_takes_only_settlements_due_by_the_cutoff() {
        settlement(merchantA, "2001-01", "100.00");
        settlement(merchantA, "2001-02", "200.00");
        settlement(merchantA, "2001-03", "300.00");
        settlement(merchantA, "2001-04", "400.00");
        settlement(merchantB, "2001-01", "50.00");
        account(merchantA, IBAN_A);

        JsonNode p = get("/api/trade/payout-tasks/preview?cutoffDate=2001-03-31", login("ADMIN")).okData();
        JsonNode a = item(p, merchantA), b = item(p, merchantB);
        assertThat(a.path("settleNos")).extracting(JsonNode::asText)
                .as("4 月那张的账期结束日晚于截止日，留待下次").containsExactly(
                        settleNo(merchantA, "2001-01"), settleNo(merchantA, "2001-02"), settleNo(merchantA, "2001-03"));
        assertThat(a.path("amount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(a.path("blockedReason").isNull()).isTrue();
        assertThat(b.path("blockedReason").asText()).isEqualTo("NO_ACCOUNT");
    }

    @Test
    @DisplayName("★★ 全流程：生成 → 自己复核被拒 → 财务复核 → 导出含完整 IBAN → 一笔失败一笔到账（AC-08 ~ AC-10）")
    void full_flow_with_one_failure() {
        settlement(merchantA, "2001-05", "120.00");
        settlement(merchantA, "2001-06", "80.00");
        settlement(merchantC, "2001-06", "70.00");
        account(merchantA, IBAN_A);
        account(merchantC, "AE450260001015333439101");
        String admin = login("ADMIN"), fin = login("FINANCE");

        JsonNode task = post("/api/trade/payout-tasks", Map.of("cutoffDate", "2001-06-30"), admin).okData();
        String taskNo = task.path("taskNo").asText();
        assertThat(task.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(task.path("items")).as("一个商家一笔").hasSize(2);
        JsonNode wa = row(task, merchantA), wc = row(task, merchantC);
        assertThat(wa.path("amount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(wa.path("accountMasked").asText()).as("运营端只给掩码").doesNotContain("0331234567890");

        assertThat(post("/api/trade/payout-tasks", Map.of("cutoffDate", "2001-06-30"), admin).status)
                .as("同一时刻至多一个未结束的任务").isEqualTo(409);
        assertThat(post("/api/trade/payout-tasks/" + taskNo + "/audit", Map.of("approve", true), admin).status)
                .as("发起人不能复核自己的任务").isEqualTo(409);
        assertThat(post("/api/trade/payout-tasks/" + taskNo + "/audit", Map.of("approve", true), fin)
                .okData().path("status").asText()).isEqualTo("PENDING_TRANSFER");

        java.net.http.HttpResponse<String> file = raw("/api/trade/payout-tasks/" + taskNo + "/export", admin);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).as("转账文件要有完整账号与附言（提现单号）")
                .contains(IBAN_A).contains(wa.path("withdrawNo").asText()).contains("200.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_payout_export WHERE task_no = ?", Integer.class, taskNo))
                .as("导出要留痕").isEqualTo(1);

        JsonNode done = post("/api/trade/payout-tasks/" + taskNo + "/confirm", Map.of(
                "bankBatchRef", "ENBD-BATCH-" + tag, "transferDate", "2001-07-05",
                "results", List.of(
                        Map.of("withdrawNo", wa.path("withdrawNo").asText(), "success", true),
                        Map.of("withdrawNo", wc.path("withdrawNo").asText(), "success", false, "failReason", "账号已销户"))),
                admin).okData();
        assertThat(done.path("status").asText()).isEqualTo("DONE");
        assertThat(row(done, merchantA).path("payRef").asText()).as("单笔流水号 = 任务号-序号").startsWith(taskNo + "-");

        assertThat(status(settleNo(merchantA, "2001-05"))).isEqualTo("PAID");
        assertThat(status(settleNo(merchantA, "2001-06"))).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT withdraw_no FROM stl_settlement WHERE settle_no = ?", String.class,
                settleNo(merchantC, "2001-06"))).as("失败的单回到可付款范围").isNull();
        assertThat(status(settleNo(merchantC, "2001-06"))).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("★ 复核驳回：整任务撤回，结算单全部放回（AC-08）")
    void rejecting_releases_the_settlements() {
        settlement(merchantA, "2001-08", "90.00");
        account(merchantA, IBAN_A);
        String taskNo = post("/api/trade/payout-tasks", Map.of("cutoffDate", "2001-08-31"), login("ADMIN"))
                .okData().path("taskNo").asText();
        String fin = login("FINANCE");

        assertThat(post("/api/trade/payout-tasks/" + taskNo + "/audit", Map.of("approve", false), fin).status)
                .as("驳回必须填原因").isEqualTo(400);
        assertThat(post("/api/trade/payout-tasks/" + taskNo + "/audit",
                Map.of("approve", false, "rejectReason", "金额待核"), fin).okData().path("status").asText())
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT withdraw_no FROM stl_settlement WHERE settle_no = ?", String.class,
                settleNo(merchantA, "2001-08"))).isNull();
    }

    /** 导出是文件下载，不走统一响应信封，ApiTestSupport 的 JSON 解析读不了它。 */
    private java.net.http.HttpResponse<String> raw(String path, String token) {
        try {
            return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String status(String settleNo) {
        return jdbc.queryForObject("SELECT status FROM stl_settlement WHERE settle_no = ?", String.class, settleNo);
    }

    private static JsonNode item(JsonNode preview, String payee) {
        for (JsonNode n : preview.path("items")) if (payee.equals(n.path("payeeNo").asText())) return n;
        throw new AssertionError("预览里没有商家 " + payee);
    }

    private static JsonNode row(JsonNode task, String payee) {
        for (JsonNode n : task.path("items")) if (payee.equals(n.path("payeeNo").asText())) return n;
        throw new AssertionError("任务里没有商家 " + payee);
    }
}
