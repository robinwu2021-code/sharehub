package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提现打款回执的端到端回归（必要功能清单 ⑮）。
 *
 * <h2>此前缺的是什么</h2>
 * 状态机里白纸黑字写着 {@code PAYING --PAY--> PAID} 和 {@code --FAIL--> FAILED}，
 * 但控制器上<b>只有建、查、审批三个端点</b> —— 没有任何入口调用那两条迁移。
 * 于是审批通过的单子推到 {@code PAYING}（出款在途）之后就<b>永远停在那里</b>：
 * 钱算得清、批得了，批完不会动。
 *
 * <p>这组用例钉住补上的那一步，以及它必须带的证据：成功要有渠道流水号、
 * 失败要有原因、同一笔流水不能登记两次。
 */
class WithdrawalPayoutTest extends ApiTestSupport {

    private static String randomPayee() {
        return "AGT" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
    }

    /** 建一笔提现并审批通过，返回单号（此时应为 PAYING）。 */
    private String approvedWithdrawal(String admin, String payeeNo) {
        Map<String, Object> acct = new HashMap<>();
        acct.put("payeeType", "AGENT");
        acct.put("payeeNo", payeeNo);
        acct.put("bankCode", "ENBD");
        acct.put("accountName", "打款回执测试");
        acct.put("accountMasked", "AE07033123456789012****");
        acct.put("currency", "AED");
        post("/api/trade/payout-accounts", acct, admin).okData();

        Map<String, Object> req = new HashMap<>();
        req.put("payeeType", "AGENT");
        req.put("payeeNo", payeeNo);
        req.put("payeeName", "打款回执测试");
        req.put("amount", 200);
        req.put("currency", "AED");
        req.put("bankCode", "ENBD");
        String no = post("/api/trade/withdrawals", req, admin).okData().path("withdrawNo").asText();

        JsonNode approved = post("/api/trade/withdrawals/" + no + "/audit",
                Map.of("approve", true), admin).okData();
        assertThat(approved.path("status").asText())
                .as("审批通过后应进入出款在途").isEqualTo("PAYING");
        return no;
    }

    @Test
    @DisplayName("★★ 提现列表要能按收款方编号筛——按名字筛会丢行")
    void withdrawalsCanBeFilteredByPayeeNo() {
        String admin = login("ADMIN");
        // 两个不同的收款方，**同一个名字** —— 现实里同名代理不罕见，
        // 而 keyword 只匹配单号与 payeeName，于是这两笔在按名字筛时混在一起。
        String a = randomPayee(), b = randomPayee();
        String na = approvedWithdrawal(admin, a);
        String nb = approvedWithdrawal(admin, b);

        // 前端的兜底是「按名字取一页，再在页内按编号过滤」。那会丢行：
        // 翻页是按名字翻的，落在当前页之外的同名记录根本不会被取回来，
        // 于是「这个代理的提现」少了几笔，而界面上看不出少了。
        JsonNode r = get("/api/trade/withdrawals?payeeNo=" + a + "&size=50", admin).okData();
        var nos = new java.util.ArrayList<String>();
        r.path("list").forEach(x -> nos.add(x.path("withdrawNo").asText()));
        assertThat(nos).as("只该有 a 的那笔").contains(na).doesNotContain(nb);
        assertThat(r.path("total").asInt()).as("total 也要按筛选口径算，否则分页是假的").isEqualTo(nos.size());

        // 反向：不传这个参数时两笔都在 —— 只验「筛得掉」会把列表筛成空也算通过。
        var all = new java.util.ArrayList<String>();
        get("/api/trade/withdrawals?size=200", admin).okData().path("list")
                .forEach(x -> all.add(x.path("withdrawNo").asText()));
        assertThat(all).as("不筛的时候两笔都看得到").contains(na, nb);
    }

    private Map<String, Object> receipt(boolean success, String channel, String ref, String reason) {
        Map<String, Object> b = new HashMap<>();
        b.put("success", success);
        b.put("channel", channel);
        if (ref != null) b.put("payRef", ref);
        if (reason != null) b.put("failReason", reason);
        return b;
    }

    private static String randomRef() {
        return "REF" + ThreadLocalRandom.current().nextLong(1_000_000_000L);
    }

    // ——————————————————— 终于能走完 ———————————————————

    @Test
    void money_can_finally_reach_a_terminal_state() {
        String admin = login("ADMIN");
        String no = approvedWithdrawal(admin, randomPayee());

        JsonNode paid = post("/api/trade/withdrawals/" + no + "/pay",
                receipt(true, "MANUAL", randomRef(), null), admin).okData();

        assertThat(paid.path("status").asText())
                .as("这一步此前整个是缺的：批完就停在 PAYING").isEqualTo("PAID");
        assertThat(paid.path("paidAt").asText()).isNotBlank();
        assertThat(paid.path("payChannel").asText()).isEqualTo("MANUAL");
        assertThat(paid.path("payerName").asText())
                .as("登记人服务端回填，便于查「谁批的、谁放的款」").isNotBlank();
    }

    @Test
    void a_failed_payout_lands_in_failed_not_paid() {
        String admin = login("ADMIN");
        String no = approvedWithdrawal(admin, randomPayee());

        JsonNode failed = post("/api/trade/withdrawals/" + no + "/pay",
                receipt(false, "MANUAL", null, "收款账号已销户"), admin).okData();

        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("failReason").asText()).isEqualTo("收款账号已销户");
        assertThat(failed.path("paidAt").asText())
                .as("没到账就不该有到账时间").isIn("", "null");
    }

    // ——————————————————— 证据不全就不许落库 ———————————————————

    @Test
    void claiming_paid_without_a_channel_reference_is_refused() {
        String admin = login("ADMIN");
        String no = approvedWithdrawal(admin, randomPayee());

        Resp r = post("/api/trade/withdrawals/" + no + "/pay",
                receipt(true, "MANUAL", null, null), admin);
        assertThat(r.code())
                .as("「已到账」要能被对账证实，没有流水号就只是一句话: %s", r.msg())
                .isNotEqualTo(0);

        // 而且不能把单子改坏 —— 拒绝之后它仍应停在 PAYING
        assertThat(statusOf(admin, no)).isEqualTo("PAYING");
    }

    @Test
    void claiming_failed_without_a_reason_is_refused() {
        String admin = login("ADMIN");
        String no = approvedWithdrawal(admin, randomPayee());

        Resp r = post("/api/trade/withdrawals/" + no + "/pay",
                receipt(false, "MANUAL", null, null), admin);
        assertThat(r.code()).as("失败必须留原因: %s", r.msg()).isNotEqualTo(0);
        assertThat(statusOf(admin, no)).isEqualTo("PAYING");
    }

    @Test
    void the_fail_reason_does_not_get_written_into_the_reject_reason_column() {
        /*
         * 「审批驳回」与「打款失败」是两件事：前者钱从没打算出去，后者出去了又退回来。
         * 合用一列的话，对账和客诉时没人分得清。
         */
        String admin = login("ADMIN");
        String no = approvedWithdrawal(admin, randomPayee());
        post("/api/trade/withdrawals/" + no + "/pay",
                receipt(false, "MANUAL", null, "银行拒收"), admin).okData();

        JsonNode row = find(admin, no);
        assertThat(row.path("failReason").asText()).isEqualTo("银行拒收");
        assertThat(row.path("rejectReason").asText())
                .as("审批驳回原因不该被打款失败污染").isIn("", "null");
    }

    // ——————————————————— 状态机与幂等 ———————————————————

    @Test
    void a_withdrawal_that_was_never_approved_cannot_be_marked_paid() {
        String admin = login("ADMIN");
        String payeeNo = randomPayee();
        Map<String, Object> req = new HashMap<>();
        req.put("payeeType", "AGENT");
        req.put("payeeNo", payeeNo);
        req.put("payeeName", "未审批");
        req.put("amount", 100);
        req.put("currency", "AED");
        String no = post("/api/trade/withdrawals", req, admin).okData().path("withdrawNo").asText();

        Resp r = post("/api/trade/withdrawals/" + no + "/pay",
                receipt(true, "MANUAL", randomRef(), null), admin);
        assertThat(r.code())
                .as("APPLY 直接跳到 PAID 等于绕过审批: %s", r.msg()).isNotEqualTo(0);
    }

    @Test
    void a_terminal_withdrawal_cannot_be_paid_twice() {
        String admin = login("ADMIN");
        String no = approvedWithdrawal(admin, randomPayee());
        post("/api/trade/withdrawals/" + no + "/pay",
                receipt(true, "MANUAL", randomRef(), null), admin).okData();

        Resp again = post("/api/trade/withdrawals/" + no + "/pay",
                receipt(true, "MANUAL", randomRef(), null), admin);
        assertThat(again.code()).as("PAID 是终态，没有出边: %s", again.msg()).isNotEqualTo(0);
    }

    @Test
    void the_same_channel_reference_cannot_be_registered_on_two_withdrawals() {
        /*
         * 同一笔银行流水被登记到两张提现单上 —— 对账时会多出一笔钱。
         * 唯一键 uk_stl_withdrawal_payref 拦在这里：**宁可报错，也不要静默写两遍**。
         */
        String admin = login("ADMIN");
        String ref = randomRef();
        String first = approvedWithdrawal(admin, randomPayee());
        String second = approvedWithdrawal(admin, randomPayee());

        post("/api/trade/withdrawals/" + first + "/pay",
                receipt(true, "MANUAL", ref, null), admin).okData();

        Resp dup = post("/api/trade/withdrawals/" + second + "/pay",
                receipt(true, "MANUAL", ref, null), admin);
        assertThat(dup.code()).as("同一笔流水不能记两次: %s", dup.msg()).isNotEqualTo(0);
        // **为正确的理由失败**：只断言「不为 0」的话，单号写错、权限不足同样能让它变绿
        assertThat(dup.msg()).as("要因为流水号重复而拒，而不是别的原因").contains("流水号");
        assertThat(statusOf(admin, second)).as("被拒的那张单不能被改坏").isEqualTo("PAYING");
    }

    @Test
    void an_unknown_channel_is_refused() {
        String admin = login("ADMIN");
        String no = approvedWithdrawal(admin, randomPayee());
        Resp r = post("/api/trade/withdrawals/" + no + "/pay",
                receipt(true, "WECHAT_RED_PACKET", randomRef(), null), admin);
        assertThat(r.code()).as("渠道白名单之外一律拒: %s", r.msg()).isNotEqualTo(0);
    }

    // ——————————————————— 权限 ———————————————————

    @Test
    void registering_a_receipt_needs_its_own_permission_code() {
        // BD 持 agent:* / location:* 等，但不持 finance:withdrawal:pay
        String admin = login("ADMIN");
        String no = approvedWithdrawal(admin, randomPayee());

        Resp r = post("/api/trade/withdrawals/" + no + "/pay",
                receipt(true, "MANUAL", randomRef(), null), login("BD"));
        assertThat(r.code()).as("无 finance:withdrawal:pay 应被拒: %s", r.msg()).isNotEqualTo(0);
    }

    private JsonNode find(String token, String withdrawNo) {
        // 保留 keyword（提现列表的 keyword 确实匹配 withdraw_no），同时翻页兜底
        JsonNode r = findInPages("/api/trade/withdrawals?keyword=" + withdrawNo, "withdrawNo", withdrawNo, token);
        if (r == null) throw new AssertionError("提现单查不到: " + withdrawNo);
        return r;
    }

    private String statusOf(String token, String withdrawNo) {
        return find(token, withdrawNo).path("status").asText();
    }
}
