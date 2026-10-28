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

    /** 运营代建并开通，返回商家号。{@code phone} 可为 null（R2）。 */
    private String opsCreate(String email, String phone, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("email", email);
        if (phone != null) b.put("phone", phone);
        b.put("operatorName", name);
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/applies", b, login("BD")).okData().path("applyNo").asText();
        String admin = login("ADMIN");
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        return post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin)
                .okData().path("operatorNo").asText();
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
        assertThat(principalByEmailOf(agentNo).get("email_verified")).as("运营录入的先记未验证（R3）").isEqualTo(0L);

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
    @DisplayName("★★ 运营录入的手机号未验证：不能用它登录（R5）；也不占唯一，号主自助入驻照样建得出来（R6）")
    void unverified_phone_neither_logs_in_nor_blocks_the_owner() {
        String p = phone();
        String typo = opsCreate(email(), p, "录错手机号 " + p);
        assertThat(((Number) principalByEmailOf(typo).get("phone_verified")).intValue()).isZero();
        assertThat(loginWith(p, OTP).code()).as("未验证的手机号收不到登录码，也登不进去").isNotEqualTo(0);

        // 真正的号主自助入驻：验证码证明了手机号，开通后这个号是他的、已验证
        post("/api/agent/apply/otp", Map.of("phone", p), null).okData();
        Map<String, Object> b = new HashMap<>();
        b.put("phone", p);
        b.put("otp", OTP);
        b.put("email", email());
        b.put("operatorName", "号主 " + p);
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/apply", b, null).okData().path("applyNo").asText();
        String admin = login("ADMIN");
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        String owner = post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin)
                .okData().path("operatorNo").asText();

        assertThat(principalByEmailOf(owner).get("principal_no")).as("不是同一个人")
                .isNotEqualTo(principalByEmailOf(typo).get("principal_no"));
        assertThat(loginWith(p, OTP).okData().path("agentNo").asText()).as("号主用手机号登进自己的商家").isEqualTo(owner);
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
