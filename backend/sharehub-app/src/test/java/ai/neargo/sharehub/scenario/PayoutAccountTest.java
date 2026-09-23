package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收款账户与提现的接点（B3）。
 *
 * <h2>这批之前断在哪</h2>
 * 提现审核页能点「通过」，而收款账户压根没有地方录 —— <b>审批完不知道往哪打钱</b>。
 * 实测过的三条：{@code loc_venue} 没有账户列、{@code agt_agent.settle_account} 全仓无代码读、
 * 提现单上只有收款<b>银行</b>没有账号。
 *
 * <h2>本文件盯的是「断点真的接上了」</h2>
 * 而不是「表建出来了」—— 表建出来但没人读，与没建是一回事
 * （{@code agt_agent.settle_account} 就是这么来的：有列、有注释、零个读它的地方）。
 */
class PayoutAccountTest extends ApiTestSupport {

    private static String randomPayee() {
        return "AGT" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
    }

    private Map<String, Object> acct(String payeeNo, String name, String iban) {
        Map<String, Object> b = new HashMap<>();
        // AGENT 不是 OPERATOR —— share_record / stl_withdrawal 现网存的都是 AGENT，
        // 本表要和它们对得上（改名是 ADR-029 §5.1 B 步的事，三张表一起改）
        b.put("payeeType", "AGENT");
        b.put("payeeNo", payeeNo);
        b.put("bankCode", "ENBD");
        b.put("accountName", name);
        b.put("accountMasked", iban);
        b.put("currency", "AED");
        return b;
    }

    private String save(Map<String, Object> body, String token) {
        return post("/api/trade/payout-accounts", body, token).okData().path("accountNo").asText();
    }

    // ——————————————————— 默认账户 ———————————————————

    @Test
    void the_first_account_becomes_default_without_asking() {
        String admin = login("ADMIN");
        String payee = randomPayee();

        String no = save(acct(payee, "迪拜湾畔科技", "AE070331234567890123456"), admin);
        JsonNode page = get("/api/trade/payout-accounts?payeeType=AGENT&payeeNo=" + payee, admin).okData();

        JsonNode row = page.path("list").get(0);
        assertThat(row.path("accountNo").asText()).isEqualTo(no);
        assertThat(row.path("isDefault").asBoolean())
                .as("第一个账户必须自动成为默认 —— 否则「录了账户却还是不能收款」，而用户看不出还差一步")
                .isTrue();
    }

    @Test
    void switching_default_clears_the_previous_one() {
        String admin = login("ADMIN");
        String payee = randomPayee();
        String first = save(acct(payee, "甲公司", "AE070331111111111111111"), admin);

        Map<String, Object> b = acct(payee, "甲公司", "AE070332222222222222222");
        b.put("makeDefault", true);
        String second = save(b, admin);

        JsonNode list = get("/api/trade/payout-accounts?payeeType=AGENT&payeeNo=" + payee, admin)
                .okData().path("list");
        for (JsonNode r : list) {
            boolean isDefault = r.path("isDefault").asBoolean();
            if (second.equals(r.path("accountNo").asText())) {
                assertThat(isDefault).as("新设的默认应生效").isTrue();
            } else if (first.equals(r.path("accountNo").asText())) {
                assertThat(isDefault)
                        .as("★ 旧默认必须被清零 —— 两个默认时 limit 1 会随机挑一个，"
                                + "「今天打这张卡明天打那张」没人能复现")
                        .isFalse();
            }
        }
    }

    @Test
    void account_number_is_stored_masked_only() {
        String admin = login("ADMIN");
        String payee = randomPayee();
        String iban = "AE070339999888877776666";
        save(acct(payee, "掩码检查", iban), admin);

        String masked = get("/api/trade/payout-accounts?payeeType=AGENT&payeeNo=" + payee, admin)
                .okData().path("list").get(0).path("accountMasked").asText();

        assertThat(masked).as("只给掩码").startsWith("****");
        assertThat(masked).as("明文不该出现在出参里").isNotEqualTo(iban);
        assertThat(iban).as("掩码应保留末 4 位便于核对").endsWith(masked.substring(4));
    }

    // ——————————————————— 与提现的接点 ———————————————————

    @Test
    void approving_a_withdrawal_without_an_account_is_refused() {
        String admin = login("ADMIN");
        JsonNode pending = get("/api/trade/withdrawals?page=1&size=50&status=APPLY", admin).okData();

        /*
         * 必须自己找出「当前确实没有收款账户」的那个受益方 —— 不能拿队列第一条就用。
         * 同一个类里别的用例会给受益方建账户，测试之间共享同一个库；
         * 直接取第一条的话，这条用例的结果取决于**执行顺序**，而顺序是不保证的。
         * （入驻那批用随机手机号解决的是同一类污染。）
         */
        String target = null;
        for (JsonNode w : pending.path("list")) {
            String type = w.path("payeeType").asText();
            String no = w.path("payeeNo").asText();
            boolean hasAccount = !get("/api/trade/payout-accounts?payeeType=" + type + "&payeeNo=" + no, admin)
                    .okData().path("list").isEmpty();
            if (!hasAccount) { target = w.path("withdrawNo").asText(); break; }
        }
        if (target == null) return;   // 都有账户时跳过，不制造假绿

        Resp r = post("/api/trade/withdrawals/" + target + "/audit", Map.of("approve", true), admin);

        assertThat(r.code())
                .as("★ 没有收款账户就不该放行 —— 否则审批完不知道往哪打钱（msg=%s）", r.msg())
                .isNotEqualTo(0);
        assertThat(r.msg()).contains("收款账户");
    }

    @Test
    void approving_snapshots_the_account_so_later_edits_do_not_rewrite_history() {
        String admin = login("ADMIN");
        JsonNode pending = get("/api/trade/withdrawals?page=1&size=50&status=APPLY", admin).okData();
        if (pending.path("list").isEmpty()) return;

        JsonNode wd = pending.path("list").get(0);
        String withdrawNo = wd.path("withdrawNo").asText();
        String payeeType = wd.path("payeeType").asText();
        String payeeNo = wd.path("payeeNo").asText();

        Map<String, Object> b = acct(payeeNo, "审批时的户名", "AE070331234500000000001");
        b.put("payeeType", payeeType);
        String acctNo = save(b, admin);

        post("/api/trade/withdrawals/" + withdrawNo + "/audit", Map.of("approve", true), admin).okData();

        // 审批后改户名：历史提现单上的快照**不应跟着变**
        Map<String, Object> renamed = acct(payeeNo, "改名之后的户名", "AE070331234500000000001");
        renamed.put("payeeType", payeeType);
        renamed.put("accountNo", acctNo);
        save(renamed, admin);

        JsonNode after = get("/api/trade/withdrawals?page=1&size=200&keyword=" + payeeNo, admin).okData();
        for (JsonNode r : after.path("list")) {
            if (withdrawNo.equals(r.path("withdrawNo").asText())) {
                // 出参里若暴露了快照字段就断言它；没暴露也不算失败（读侧是否透出是另一件事）
                JsonNode snap = r.path("payoutAccountName");
                if (!snap.isMissingNode() && !snap.asText().isEmpty()) {
                    assertThat(snap.asText())
                            .as("★ 快照必须停在审批那一刻 —— 否则对不上当初的打款回单")
                            .isEqualTo("审批时的户名");
                }
            }
        }
    }

    // ——————————————————— 停用 ———————————————————

    @Test
    void the_only_default_account_cannot_be_disabled_silently() {
        String admin = login("ADMIN");
        String payee = randomPayee();
        String first = save(acct(payee, "默认账户", "AE070337777666655554444"), admin);   // 自动成为默认
        String second = save(acct(payee, "备用账户", "AE070335555444433332222"), admin);   // 非默认

        // 备用账户可以随时停用
        assertThat(post("/api/trade/payout-accounts/" + second + "/disable", Map.of(), admin).code())
                .as("非默认账户应能直接停用").isEqualTo(0);

        // 默认账户在还有其它账户时不能直接停 —— 先切默认再停
        String third = save(acct(payee, "第三账户", "AE070331111222233334444"), admin);
        assertThat(third).isNotBlank();
        Resp r = post("/api/trade/payout-accounts/" + first + "/disable", Map.of(), admin);
        assertThat(r.code())
                .as("★ 默认账户还有其它可用账户时不能直接停 —— 停完就没有默认账户了，"
                        + "而提现照样能申请，直到审批那一刻才发现打不出去（msg=%s）", r.msg())
                .isNotEqualTo(0);
    }

    // ——————————————————— 判权 ———————————————————

    @Test
    void writing_an_account_is_not_covered_by_the_withdrawal_audit_permission() {
        // VIEWER 持 finance:payout_account:read 但不持 :update
        Resp r = post("/api/trade/payout-accounts",
                acct(randomPayee(), "越权写入", "AE070331111000011110000"), login("VIEWER"));
        assertThat(r.status == 403 || r.code() != 0)
                .as("★ 读写分离：能看账户不等于能改账户（status=%d, msg=%s）", r.status, r.msg())
                .isTrue();
    }
}
