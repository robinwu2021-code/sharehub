package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.SelfServiceApplyOpen;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家一期 B2：邮箱优先登录、手机号选填、未验证的标识不占唯一（V168，需求方案 §四 R1–R8，验收 AC-01 / AC-02）。
 *
 * <p>代建走 BD 提交、管理员受理与通过（四眼：提交人 ≠ 审核人）。
 */
@org.springframework.test.context.TestPropertySource(properties = {
        "sharehub.security.apply-rate-per-minute=10000",
})
@SelfServiceApplyOpen
class MerchantEmailLoginTest extends ApiTestSupport {

    private static final String OTP = "000000";

    @Autowired
    private JdbcTemplate jdbc;

    private static String email() {
        return "m" + System.nanoTime() + "@example.com";
    }

    private static String phone() {
        return "+97152" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_000_000));
    }

    /** 运营代建并开通，返回审核结果（含商家号与一次性初始口令）。{@code phone} 可为 null（R2）。 */
    private JsonNode opsCreateResult(String email, String phone, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("email", email);
        if (phone != null) b.put("phone", phone);
        b.put("operatorName", name);
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/applies", b, login("BD")).okData().path("applyNo").asText();
        String admin = login("ADMIN");
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        return post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin).okData();
    }

    private String opsCreate(String email, String phone, String name) {
        return opsCreateResult(email, phone, name).path("operatorNo").asText();
    }

    private Resp passwordLogin(String identifier, String password) {
        return post("/api/auth/login", Map.of("realm", "AGENT", "identifier", identifier, "password", password), null);
    }

    private Resp loginWith(String identifier, String otp) {
        post("/api/auth/otp", Map.of("identifier", identifier), null);
        return post("/api/auth/login", Map.of("realm", "AGENT", "identifier", identifier, "otp", otp), null);
    }

    private Map<String, Object> principalByEmailOf(String agentNo) {
        return jdbc.queryForMap("SELECT p.principal_no, CAST(p.email_verified AS SIGNED) email_verified, CAST(p.phone_verified AS SIGNED) phone_verified, p.phone_hash FROM agt_principal p "
                + "JOIN agt_account a ON a.principal_no = p.principal_no WHERE a.agent_no = ? LIMIT 1", agentNo);
    }

    @Test
    @DisplayName("★★ 只填邮箱代建 → 用邮箱收码首登成功，邮箱记为已验证（AC-01 / AC-02 / R4）")
    void email_only_merchant_logs_in_by_email_and_gets_verified() {
        String mail = email();
        String agentNo = opsCreate(mail, null, "邮箱登录 " + mail);
        assertThat(((Number) principalByEmailOf(agentNo).get("email_verified")).intValue()).as("运营录入的先记未验证（R3）").isZero();

        JsonNode s = loginWith("  " + mail.toUpperCase() + " ", OTP).okData();
        assertThat(s.path("agentNo").asText()).as("大小写与首尾空格归一后是同一个邮箱").isEqualTo(agentNo);
        assertThat(((Number) principalByEmailOf(agentNo).get("email_verified")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 同一邮箱代建两个商家 → 同一个人、两个身份可切换（R7）")
    void same_email_two_merchants_one_person() {
        String mail = email();
        String a = opsCreate(mail, null, "同邮箱甲 " + mail);
        String b = opsCreate(mail, null, "同邮箱乙 " + mail);
        assertThat(principalByEmailOf(a).get("principal_no")).isEqualTo(principalByEmailOf(b).get("principal_no"));

        String token = loginWith(mail, OTP).okData().path("token").asText();
        JsonNode ops = get("/api/auth/operators", token).okData();
        assertThat(ops).extracting(n -> n.path("agentNo").asText()).contains(a, b);
    }

    @Test
    @DisplayName("★★ 运营代录的手机号也能登录（用户 2026-10-28），收码登录后记为已验证")
    void ops_entered_phone_can_log_in() {
        String p = phone();
        String agentNo = opsCreate(email(), p, "代录手机号 " + p);
        assertThat(((Number) principalByEmailOf(agentNo).get("phone_verified")).intValue()).isZero();
        assertThat(loginWith(p, OTP).okData().path("agentNo").asText()).isEqualTo(agentNo);
        assertThat(((Number) principalByEmailOf(agentNo).get("phone_verified")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 开通给一次性初始口令：邮箱或手机号 + 口令登录，首登必须改；改完用新口令登录不再要求")
    void initial_password_then_forced_change() {
        String mail = email(), p = phone();
        JsonNode r = opsCreateResult(mail, p, "初始口令 " + mail);
        String temp = r.path("initialPassword").asText();
        assertThat(temp).as("新的人开通即发一次性口令").isNotBlank();
        // 发送流水只存掩码（日志规范），所以按「发生过」核对，不按地址
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_log WHERE scene = 'MERCHANT_INVITE' AND channel = 'EMAIL' "
                + "AND created_at >= NOW() - INTERVAL 1 MINUTE", Integer.class)).as("开通即寄邀请邮件").isPositive();

        JsonNode s = passwordLogin(mail, temp).okData();
        assertThat(s.path("mustChange").asBoolean()).isTrue();
        assertThat(passwordLogin(p, temp).okData().path("agentNo").asText()).as("手机号 + 口令同样能登").isEqualTo(r.path("operatorNo").asText());

        post("/api/auth/password", Map.of("oldPassword", temp, "newPassword", "Merchant#2026x"), s.path("token").asText()).okData();
        assertThat(passwordLogin(mail, "Merchant#2026x").okData().path("mustChange").asBoolean()).isFalse();
        assertThat(passwordLogin(mail, temp).status).as("旧口令作废").isEqualTo(401);
    }

    @Test
    @DisplayName("★ 同一个人开第二家不重置口令；重发邀请作废旧口令、发新的；查无此人与口令错同一句话")
    void second_merchant_keeps_password_and_resend_rotates_it() {
        String mail = email();
        String first = opsCreateResult(mail, null, "口令甲 " + mail).path("initialPassword").asText();
        JsonNode second = opsCreateResult(mail, null, "口令乙 " + mail);
        assertThat(second.path("initialPassword").isNull()).as("一个人一套口令").isTrue();

        String fresh = post("/api/agent/agents/" + second.path("operatorNo").asText() + "/invite", Map.of(), login("ADMIN"))
                .okData().path("initialPassword").asText();
        assertThat(fresh).isNotBlank().isNotEqualTo(first);
        assertThat(passwordLogin(mail, first).status).isEqualTo(401);
        assertThat(passwordLogin(mail, fresh).okData().path("mustChange").asBoolean()).isTrue();

        Resp unknown = passwordLogin(email(), fresh), wrong = passwordLogin(mail, "nope");
        assertThat(unknown.status).isEqualTo(401);
        assertThat(unknown.msg()).isEqualTo(wrong.msg());
    }

    @Test
    @DisplayName("★ 查无此邮箱与验证码错误：发码都成功，登录失败说同一句话（防枚举）")
    void unknown_email_and_wrong_code_look_the_same() {
        String known = email();
        opsCreate(known, null, "防枚举 " + known);
        String unknown = email();
        assertThat(post("/api/auth/otp", Map.of("identifier", unknown), null).status).isEqualTo(200);
        Resp a = loginWith(unknown, OTP);
        Resp b = loginWith(known, "123456");
        assertThat(a.code()).isNotEqualTo(0);
        assertThat(a.msg()).isEqualTo(b.msg());
    }

    @Test
    @DisplayName("同一邮箱至多一张在途申请（V168 起在途唯一按邮箱）")
    void one_in_flight_application_per_email() {
        String mail = email();
        Map<String, Object> b = new HashMap<>();
        b.put("email", mail);
        b.put("operatorName", "在途甲 " + mail);
        b.put("operatorType", "AGENT");
        post("/api/agent/applies", b, login("BD")).okData();
        b.put("operatorName", "在途乙 " + mail);
        assertThat(post("/api/agent/applies", b, login("BD")).status).isEqualTo(400);
    }
}
