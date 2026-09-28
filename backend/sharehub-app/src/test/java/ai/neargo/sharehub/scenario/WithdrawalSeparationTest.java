package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提现：审批人不得兼放款人 —— 三态，由管理员配（2026-09-28 裁决）。
 * 方案见 {@code docs/technical/方案-提现审批与放款分离.md}。
 *
 * <h2>为什么不硬拦</h2>
 * 拦错了的后果是**钱打不出去**，而小团队只有一个财务是常态。
 * 本仓栽过同形的一次：合同双人复核两道闸写得全对，但生产只有一个账号时
 * 任何合同都进不了 ACTIVE，只能靠改库自救。
 *
 * <h2>四条各守什么</h2>
 * <ol>
 *   <li>{@code REQUIRE}（默认）同一人放款 → 409；</li>
 *   <li>{@code WARN} 放行，但 {@code same_operator=1}；</li>
 *   <li>{@code OFF} 放行，{@code same_operator} <b>仍然是 1</b> ——
 *       关掉的是拦截，不是记录；</li>
 *   <li><b>反向断言</b>：两个不同的人，三种配置下都放行且标记为 0。
 *       少了这条，「把判定写反」会全绿通过。</li>
 * </ol>
 *
 * <h2>不依赖种子里恰好有两个财务</h2>
 * 审批与放款用**两个不同的角色令牌**，角色权限由用例自己临时插 ——
 * 依赖种子数据的话，别人改种子这条就红，而那与它要守的事无关。
 */
class WithdrawalSeparationTest extends ApiTestSupport {

    private static final String KEY = "finance.withdrawal.separation";
    /** 只给「审批+放款」两个码的角色。两个实例 = 两个不同的 userNo。 */
    private static final String ROLE_A = "TMPFIN_A";
    private static final String ROLE_B = "TMPFIN_B";

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM sys_param WHERE param_key=?", KEY);
        jdbc.update("DELETE FROM iam_role_perm WHERE role_no IN (?,?)", ROLE_A, ROLE_B);
        jdbc.update("DELETE FROM stl_withdrawal WHERE payee_name LIKE '【分离测试】%'");
    }

    @Test
    @DisplayName("★ REQUIRE（默认）：同一人放款 → 409，且单据不被改动")
    void require_blocks_the_same_operator() {
        setMode(null);                       // 不配 = REQUIRE
        String finA = financeToken(ROLE_A);
        String no = approvedWithdrawal(finA);

        Resp r = pay(no, finA);
        assertThat(r.status).as("同一人放款没被拦：%s", r.body).isEqualTo(409);

        // 被拒之后不该留下任何痕迹 —— 状态没变、标记没落
        Map<String, Object> row = rowOf(no);
        assertThat(row.get("status")).as("被拒了还改了状态").isEqualTo("PAYING");
        assertThat(num(row.get("same_operator"))).as("被拒了不该落标记").isZero();
    }

    @Test
    @DisplayName("★ WARN：放行，但 same_operator 落 1 —— 允许不等于不留痕")
    void warn_allows_but_marks() {
        setMode("WARN");
        String finA = financeToken(ROLE_A);
        String no = approvedWithdrawal(finA);

        assertThat(pay(no, finA).status).as("WARN 模式下不该拦").isEqualTo(200);
        assertThat(num(rowOf(no).get("same_operator"))).as("WARN 必须留痕").isEqualTo(1);
    }

    @Test
    @DisplayName("★ OFF：放行，same_operator 仍然是 1 —— 关掉的是拦截，不是记录")
    void off_still_records() {
        setMode("OFF");
        String finA = financeToken(ROLE_A);
        String no = approvedWithdrawal(finA);

        assertThat(pay(no, finA).status).isEqualTo(200);
        assertThat(num(rowOf(no).get("same_operator")))
                .as("OFF 把记录也关掉了 —— 那样「当时允许了」这件事就没了")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 反向：两个不同的人，三种配置都放行且标记为 0（防「判反了还全绿」）")
    void two_different_people_always_pass() {
        for (String mode : new String[]{null, "WARN", "OFF"}) {
            setMode(mode);
            String finA = financeToken(ROLE_A);
            String finB = financeToken(ROLE_B);
            String no = approvedWithdrawal(finA);

            assertThat(pay(no, finB).status)
                    .as("mode=%s：不同的人放款被拦了 —— 判定写反了？", mode).isEqualTo(200);
            assertThat(num(rowOf(no).get("same_operator")))
                    .as("mode=%s：不同的人却标成了同一人", mode).isZero();
        }
    }

    // ——————————————————————— 夹具 ———————————————————————

    private void setMode(String mode) {
        jdbc.update("DELETE FROM sys_param WHERE param_key=?", KEY);
        if (mode != null) {
            jdbc.update("""
                    INSERT INTO sys_param (param_key, tenant_id, value, label, group_name)
                    VALUES (?, 'MAIN', ?, '提现审批放款分离', '财务')
                    """, KEY, mode);
        }
    }

    /** 临时角色：只给审批与放款两个码，登录后即是一个独立的 userNo。 */
    private String financeToken(String role) {
        jdbc.update("DELETE FROM iam_role_perm WHERE role_no=?", role);
        jdbc.update("""
                INSERT INTO iam_role_perm (role_no, perm_code) VALUES
                  (?, 'finance:withdrawal:read'), (?, 'finance:withdrawal:apply'),
                  (?, 'finance:withdrawal:audit'), (?, 'finance:withdrawal:pay'),
                  (?, 'finance:payout_account:update')
                """, role, role, role, role, role);
        return login(role);
    }

    /** 造一张已审批（PAYING）的提现单，审批人 = 传入的令牌。 */
    private String approvedWithdrawal(String token) {
        // 用 ENABLED 的代理：AG001 在库里是 SUSPENDED，申请提现会被
        // requireAgentNotSuspended 拦成 409，而那与本用例要守的事无关
        String payeeNo = "AG002";
        Map<String, Object> acct = new HashMap<>();
        acct.put("payeeType", "AGENT");
        acct.put("payeeNo", payeeNo);
        acct.put("bankCode", "ENBD");
        acct.put("accountName", "【分离测试】收款户");
        acct.put("accountMasked", "AE07033123456789012****");
        acct.put("currency", "AED");
        post("/api/trade/payout-accounts", acct, token).okData();

        Map<String, Object> req = new HashMap<>();
        req.put("payeeType", "AGENT");
        req.put("payeeNo", payeeNo);
        req.put("payeeName", "【分离测试】收款方");
        req.put("amount", 100);
        req.put("currency", "AED");
        req.put("bankCode", "ENBD");
        String no = post("/api/trade/withdrawals", req, token).okData().path("withdrawNo").asText();

        assertThat(post("/api/trade/withdrawals/" + no + "/audit", Map.of("approve", true), token)
                .okData().path("status").asText()).isEqualTo("PAYING");
        return no;
    }

    private Resp pay(String no, String token) {
        return post("/api/trade/withdrawals/" + no + "/pay",
                Map.of("success", true, "channel", "MANUAL", "payRef", "TEST-" + no), token);
    }

    private Map<String, Object> rowOf(String no) {
        return jdbc.queryForMap(
                "SELECT status, same_operator FROM stl_withdrawal WHERE withdraw_no=?", no);
    }

    /** TINYINT(1) 经 JDBC 回来是 Boolean 而不是 Number —— 两种都收，别在这上面红。 */
    private static int num(Object v) {
        if (v == null) return 0;
        if (v instanceof Boolean b) return b ? 1 : 0;
        return ((Number) v).intValue();
    }
}
