package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.finance.service.ReconcileService;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
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
 * 对账差错处置的两处留痕缺陷 —— 都只在「钱对不上之后要查是谁怎么判的」时才发作。
 *
 * <ol>
 *   <li><b>处置人由调用方提供</b>。控制器从请求体读 {@code operatorName}，服务里写的是
 *       「有传参就用传参、否则才看会话」—— 服务端明明知道是谁，却<b>优先信调用方说的</b>。
 *       {@code recon_task.handled_by} 正是对账要审的那一列。
 *       同一个契约文件里的邻居（提现审批/结算确认/开票作废）都注着
 *       「后端以会话为准，前端透传便于 mock」，只有这里真的反了过来。</li>
 *   <li><b>逐笔处置的结论会被下一笔覆盖</b>。resolve 本来就收 {@code diffId}（逐笔是支持的路径），
 *       但结论/说明/处置人只写在批次上：把 A 判成 verify「凭证号 X」、再把 B 判成
 *       channel「渠道少记」，批次上只剩后者，A 的判断没了。
 *       类里已经为「重复处置整批会覆盖留痕」加过防护，逐笔这条漏了。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconHandleTraceTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReconcileService reconciles;

    final List<String> batches = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String b : batches) {
            jdbc.update("DELETE FROM recon_diff WHERE batch_no=?", b);
            jdbc.update("DELETE FROM recon_task WHERE batch_no=?", b);
        }
    }

    @Test
    @DisplayName("处置人只认会话：请求体里的 operatorName 一律不作数")
    void handledByComesFromTheSessionNotTheBody() {
        String batch = batch();
        long diffId = diff(batch, "PAYX" + rnd());

        var resp = post("/api/trade/reconciles/" + batch + "/resolve",
                Map.of("action", "verify", "handleNote", "核对无误，凭证 V1",
                        "diffId", diffId, "operatorName", "别人的名字"),
                login("ADMIN"));
        resp.okData();

        assertThat(jdbc.queryForObject("SELECT handled_by FROM recon_task WHERE batch_no=?", String.class, batch))
                .as("记的必须是会话里那个人 —— 传什么就记什么等于审计链随手可伪造")
                .isNotEqualTo("别人的名字");
        assertThat(jdbc.queryForObject("SELECT handled_by FROM recon_diff WHERE id=?", String.class, diffId))
                .isNotEqualTo("别人的名字");
    }

    @Test
    @DisplayName("逐笔处置各记各的结论 —— 后一笔不该把前一笔的判断抹掉")
    void perDiffConclusionsSurviveTheNextOne() {
        String batch = batch();
        long a = diff(batch, "PAYA" + rnd());
        long b = diff(batch, "PAYB" + rnd());

        reconciles.resolve(batch, a, "verify", "A：逐笔核对无误，凭证 V2044");
        reconciles.resolve(batch, b, "channel", "B：渠道少记，已提工单 NP-1");

        Map<String, Object> rowA = jdbc.queryForMap(
                "SELECT handle_result, handle_note, handled_by, handled_at FROM recon_diff WHERE id=?", a);
        Map<String, Object> rowB = jdbc.queryForMap(
                "SELECT handle_result, handle_note FROM recon_diff WHERE id=?", b);

        assertThat(rowA).as("A 的结论不该被 B 的覆盖").containsEntry("handle_result", "VERIFIED_OK");
        assertThat(String.valueOf(rowA.get("handle_note"))).contains("V2044");
        assertThat(rowA.get("handled_by")).as("逐笔也要记处置人").isNotNull();
        assertThat(rowA.get("handled_at")).isNotNull();
        assertThat(rowB).containsEntry("handle_result", "CHANNEL_ERROR");

        // 出参也要带上，否则页面仍然只看得到批次那一份（被覆盖过的那份）
        JsonNode diffs = get("/api/trade/reconciles/" + batch + "/diffs", login("ADMIN")).okData();
        JsonNode first = diffs.path(0);
        assertThat(first.path("handleResult").asText()).isEqualTo("VERIFIED_OK");
        assertThat(first.path("handledBy").asText()).isNotBlank();
    }

    // —— 夹具 ——

    /** recon_task 有 uk_recon_channel_date(tenant_id, channel, bill_date) —— 一个渠道一天只一批，
     *  所以每个夹具往前挪一天，两个用例才能各建一批。 */
    private final java.util.concurrent.atomic.AtomicInteger dayOffset = new java.util.concurrent.atomic.AtomicInteger(1);

    private String batch() {
        String no = "RCB" + rnd();
        int back = dayOffset.getAndIncrement();
        batches.add(no);
        jdbc.update("INSERT INTO recon_task (batch_no, tenant_id, channel, period, bill_date, status,"
                + " handle_status, nearpay_total, ledger_total, diff, currency)"
                + " VALUES (?, 'MAIN', 'NEARPAY', DATE_FORMAT(CURDATE(), '%Y-%m'),"
                + " DATE_SUB(CURDATE(), INTERVAL ? DAY), 'DIFF', 'OPEN', 100, 90, 10, 'AED')", no, back);
        return no;
    }

    private long diff(String batchNo, String payNo) {
        jdbc.update("INSERT INTO recon_diff (tenant_id, batch_no, pay_no, diff_type, amount, currency, resolved)"
                + " VALUES ('MAIN', ?, ?, 'AMOUNT_MISMATCH', 5, 'AED', 0)", batchNo, payNo);
        return jdbc.queryForObject("SELECT id FROM recon_diff WHERE batch_no=? AND pay_no=?", Long.class, batchNo, payNo);
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
