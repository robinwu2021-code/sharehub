package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 提现审批的**合规四件套**验收：手续费 / 审批人 / 审批时间 / 驳回原因。
 *
 * <p>背景：这四件套在 {@code WithdrawalServiceImpl.audit()} 里早就实现了，但
 * {@code POST /api/trade/withdrawals/{no}/audit} 的路由一直指向 {@code TradeController} 的
 * <b>内存实现</b> —— 那版直接把状态置 PAYING/FAILED，四件套一个都不落。
 * 「代码写了但走不到」比「没写」更危险，因为读代码会以为已经合规。
 *
 * <p>所以这些断言检查的是**端到端出参**而不是 service 单测 —— 只有走完整条路由才能证明没被旁路。
 */
class WithdrawalAuditComplianceTest extends ApiTestSupport {

    /**
     * 每个用例自建提现单，不复用固定编号。
     *
     * <p>最初写成固定的 WD9001/WD9002，单跑通过、全量跑失败 —— 因为审批会改状态，
     * 第二次再审同一条就是非法迁移。**审批类测试必须自备数据**，否则它只在第一次运行时有效。
     * 顺带这样也真正走了一遍申请流程。
     */
    private String freshWithdrawal() {
        Map<String, Object> req = new HashMap<>();
        req.put("payeeType", "AGENT");
        req.put("payeeNo", "AG002");
        req.put("payeeName", "测试代理");
        req.put("amount", 1000);
        req.put("currency", "AED");
        req.put("bankCode", "ADCB");
        return post("/api/trade/withdrawals", req, login("FINANCE")).okData().path("withdrawNo").asText();
    }

    @Test
    void list_returns_compliance_fields_not_the_legacy_six_field_shape() {
        JsonNode page = get("/api/trade/withdrawals?page=1&size=50", login("FINANCE")).okData();
        assumeTrue(page.path("list").size() > 0, "无提现单——跳过");

        JsonNode row = page.path("list").get(0);
        // 旧内存 VO 只有 withdrawNo/payeeName/amount/currency/status/appliedAt 六个字段，
        // 这四个字段的存在本身就证明路由已切到落库版
        for (String f : new String[]{"fee", "auditorName", "auditedAt", "rejectReason"}) {
            assertThat(row.has(f)).as("列表出参应含合规字段 %s（缺失说明仍是旧内存实现）", f).isTrue();
        }
        assertThat(row.has("netAmount")).as("应含派生的实际到账").isTrue();
    }

    @Test
    void reject_without_reason_is_refused() {
        // 驳回不填原因 = 没有审批记录，必须被拒
        Map<String, Object> body = new HashMap<>();
        body.put("approve", false);
        assertThat(post("/api/trade/withdrawals/" + freshWithdrawal() + "/audit", body, login("FINANCE")).status)
                .as("驳回未填原因应被拒绝")
                .isEqualTo(400);
    }

    @Test
    void reject_records_reason_and_auditor() {
        Map<String, Object> body = new HashMap<>();
        body.put("approve", false);
        body.put("rejectReason", "银行账户信息不符");
        // 前端契约会传 auditorName —— 故意传一个假的，验证服务端不采纳
        body.put("auditorName", "伪造的审批人");

        JsonNode w = post("/api/trade/withdrawals/" + freshWithdrawal() + "/audit", body, login("FINANCE")).okData();

        assertThat(w.path("status").asText()).isEqualTo("FAILED");
        assertThat(w.path("rejectReason").asText()).isEqualTo("银行账户信息不符");
        assertThat(w.path("auditedAt").asText()).as("审批时间应由服务端回填").isNotBlank();
        assertThat(w.path("auditorName").asText())
                .as("审批人必须取当前登录态，不能采纳入参里的伪造值")
                .isNotEqualTo("伪造的审批人");
    }

    @Test
    void approve_keeps_fee_and_derives_net_amount() {
        JsonNode w = post("/api/trade/withdrawals/" + freshWithdrawal() + "/audit",
                Map.of("approve", true), login("FINANCE")).okData();

        assertThat(w.path("status").asText()).isEqualTo("PAYING");
        assertThat(w.path("auditorName").asText()).as("通过也要留审批人").isNotBlank();
        assertThat(w.path("auditedAt").asText()).isNotBlank();
        assertThat(w.path("rejectReason").isNull() || w.path("rejectReason").asText().isEmpty())
                .as("通过时不该有驳回原因").isTrue();

        double amount = w.path("amount").asDouble();
        double fee = w.path("fee").asDouble();
        assertThat(w.path("netAmount").asDouble())
                .as("实际到账 = 金额 - 手续费（派生值，不落库）")
                .isEqualTo(amount - fee);
    }

    @Test
    void cs_cannot_audit_withdrawal() {
        // 审批是财务权限，客服不该能碰资金审批
        assertThat(post("/api/trade/withdrawals/" + freshWithdrawal() + "/audit",
                Map.of("approve", true), login("CS")).status)
                .as("CS 审批提现应 403")
                .isEqualTo(403);
    }
}
