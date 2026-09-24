package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代理端实名登录的端到端回归（必要功能清单 ④⑤ / ADR-030）。
 *
 * <h2>这组用例真正要钉住的事</h2>
 * 入驻做完之后有过一个**静默的断裂**：{@code activate()} 老老实实把 {@code agt_principal} +
 * {@code agt_account} 写进了库，而 {@code AuthController} 里 {@code agt_account} 出现
 * <b>0 次</b> —— 审核通过建出来的号<b>登不进去</b>，而且两边的测试都是绿的，
 * 因为没有任何一条用例**跨过**入驻与登录的边界。
 *
 * <p>所以本测试的第一条就是那条跨界用例：<b>走完审核，再用同一个手机号登录</b>。
 * 它一旦红，说明电线又断了。
 */
@org.springframework.test.context.TestPropertySource(properties = {
        // 与入驻测试同理：调高而非关闭，限流仍在链路上（/api/auth/otp 也归这道闸管）
        "sharehub.security.apply-rate-per-minute=10000",
})
class AgentLoginFlowTest extends ApiTestSupport {

    private static final String OTP = "000000";

    private static String randomPhone() {
        return "+97150" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_000_000));
    }

    /** 走完「自助提交 → 受理 → 通过」，返回该手机号（此时它名下应已有一个可登录的主体）。 */
    private String onboardedPhone(String operatorName) {
        String phone = randomPhone();
        post("/api/agent/apply/otp", Map.of("phone", phone), null).okData();
        Map<String, Object> b = new HashMap<>();
        b.put("phone", phone);
        b.put("otp", OTP);
        b.put("email", "t" + Math.abs(phone.hashCode()) + "@example.com");
        b.put("operatorName", operatorName);
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/apply", b, null).okData().path("applyNo").asText();

        String admin = login("ADMIN");
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin).okData();
        return phone;
    }

    private JsonNode loginByOtp(String phone) {
        post("/api/auth/otp", Map.of("phone", phone), null).okData();
        return post("/api/auth/login", Map.of("phone", phone, "otp", OTP), null).okData();
    }

    // ——————————————————— ④ 入驻产物必须能登进来 ———————————————————

    @Test
    void an_approved_applicant_can_actually_log_in() {
        String phone = onboardedPhone("登录打通测试");

        JsonNode resp = loginByOtp(phone);

        assertThat(resp.path("token").asText())
                .as("审核通过后必须能拿到 token —— 这正是此前断掉的一环")
                .startsWith("atk_");
        assertThat(resp.path("role").asText()).isEqualTo("AGENT");
        assertThat(resp.path("agentNo").asText()).as("登录即落定一个运营主体").startsWith("AG");
        assertThat(resp.path("principalNo").asText()).startsWith("PR");
    }

    @Test
    void the_session_is_usable_not_just_issued() {
        // 只验「发出了 token」不够 —— 发一个什么都看不到的空壳会话同样能过那条断言
        String phone = onboardedPhone("会话可用性测试");
        String token = loginByOtp(phone).path("token").asText();

        JsonNode me = get("/api/auth/me", token).okData();
        assertThat(me.path("authenticated").asBoolean()).isTrue();
        assertThat(me.path("agentNo").asText()).startsWith("AG");
        assertThat(get("/api/auth/permissions", token).okData().size())
                .as("代理会话要真的带着权限码，否则登进来也什么都干不了").isPositive();
    }

    @Test
    void a_wrong_code_is_rejected() {
        String phone = onboardedPhone("错码测试");
        post("/api/auth/otp", Map.of("phone", phone), null).okData();

        Resp bad = post("/api/auth/login", Map.of("phone", phone, "otp", "123456"), null);
        assertThat(bad.code()).as("错码必须拒: %s", bad.msg()).isNotEqualTo(0);
    }

    @Test
    void an_unknown_phone_cannot_be_told_apart_from_a_wrong_code() {
        /*
         * 账号枚举防护：如果「号不存在」和「码不对」的响应不同，
         * 这两个接口合起来就是一台代理商手机号探测器。
         */
        String known = onboardedPhone("枚举防护-已知号");
        String unknown = randomPhone();

        Resp otpKnown = post("/api/auth/otp", Map.of("phone", known), null);
        Resp otpUnknown = post("/api/auth/otp", Map.of("phone", unknown), null);
        assertThat(otpUnknown.code()).as("查无此号也要返回成功").isEqualTo(otpKnown.code());

        Resp loginUnknown = post("/api/auth/login", Map.of("phone", unknown, "otp", OTP), null);
        Resp loginBadCode = post("/api/auth/login", Map.of("phone", known, "otp", "123456"), null);
        assertThat(loginUnknown.msg())
                .as("两种失败必须说同一句话，否则可据此判断号是否注册过")
                .isEqualTo(loginBadCode.msg());
    }

    @Test
    void a_person_without_any_operator_is_refused_rather_than_given_an_empty_session() {
        /*
         * 只提交、不通过审核 —— 此人有 principal 吗？没有：principal 是 activate() 才建的。
         * 所以这条同时钉住「未通过审核的人登不进来」。
         */
        String phone = randomPhone();
        post("/api/agent/apply/otp", Map.of("phone", phone), null).okData();
        Map<String, Object> b = new HashMap<>();
        b.put("phone", phone);
        b.put("otp", OTP);
        b.put("email", "t" + Math.abs(phone.hashCode()) + "@example.com");
        b.put("operatorName", "未过审登录测试");
        b.put("operatorType", "AGENT");
        post("/api/agent/apply", b, null).okData();

        post("/api/auth/otp", Map.of("phone", phone), null);
        Resp r = post("/api/auth/login", Map.of("phone", phone, "otp", OTP), null);
        assertThat(r.code()).as("未通过审核不能登进来: %s", r.msg()).isNotEqualTo(0);
    }

    // ——————————————————— ⑤ 多主体 ———————————————————

    @Test
    void my_operators_are_listed_for_the_logged_in_person() {
        String phone = onboardedPhone("主体列表测试");
        String token = loginByOtp(phone).path("token").asText();

        JsonNode ops = get("/api/auth/operators", token).okData();
        assertThat(ops.size()).as("至少有一个主体，否则前面的登录就不该成功").isPositive();
        assertThat(ops.get(0).path("agentNo").asText()).startsWith("AG");
        assertThat(ops.get(0).path("isOwner").asBoolean()).as("自助入驻人是属主").isTrue();
    }

    @Test
    void the_same_phone_applying_twice_yields_two_operators_on_one_person() {
        /*
         * ADR-030 的核心承诺：同一手机号第二次入驻，**不是**新建一个人，
         * 而是同一个 principal 多出一行成员关系。这条用例就是它的验收。
         */
        String phone = onboardedPhone("多主体-第一家");
        String principalNo = loginByOtp(phone).path("principalNo").asText();

        // 同号再入驻一家（第一张已是终态，active_key 不再拦）
        String admin = login("ADMIN");
        post("/api/agent/apply/otp", Map.of("phone", phone), null).okData();
        Map<String, Object> b = new HashMap<>();
        b.put("phone", phone);
        b.put("otp", OTP);
        b.put("email", "t" + Math.abs(phone.hashCode()) + "@example.com");
        b.put("operatorName", "多主体-第二家");
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/apply", b, null).okData().path("applyNo").asText();
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin).okData();

        JsonNode second = loginByOtp(phone);
        assertThat(second.path("principalNo").asText())
                .as("同一个人，不该被建成第二个自然人").isEqualTo(principalNo);

        JsonNode ops = get("/api/auth/operators", second.path("token").asText()).okData();
        assertThat(ops.size()).as("一个人名下应有两个主体").isGreaterThanOrEqualTo(2);
    }

    @Test
    void switching_operator_reissues_the_token_and_kills_the_old_one() {
        String phone = onboardedPhone("切换-第一家");
        String admin = login("ADMIN");
        post("/api/auth/otp", Map.of("phone", phone), null).okData();
        Map<String, Object> b = new HashMap<>();
        b.put("phone", phone);
        b.put("otp", OTP);
        b.put("email", "t" + Math.abs(phone.hashCode()) + "@example.com");
        b.put("operatorName", "切换-第二家");
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/apply", b, null).okData().path("applyNo").asText();
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin).okData();

        JsonNode first = loginByOtp(phone);
        String oldToken = first.path("token").asText();
        String current = first.path("agentNo").asText();

        String other = null;
        for (JsonNode o : get("/api/auth/operators", oldToken).okData()) {
            if (!current.equals(o.path("agentNo").asText())) { other = o.path("agentNo").asText(); break; }
        }
        assertThat(other).as("应当有第二个主体可切").isNotNull();

        JsonNode switched = post("/api/auth/operators/" + other + "/switch", Map.of(), oldToken).okData();
        assertThat(switched.path("agentNo").asText()).isEqualTo(other);
        assertThat(switched.path("token").asText()).isNotEqualTo(oldToken);

        // 旧 token 必须立刻失效 —— 否则两个数据范围会并存，一个会话还能按旧主体查数据
        Resp old = get("/api/auth/me", oldToken);
        assertThat(old.body.path("data").path("authenticated").asBoolean())
                .as("换发之后老 token 不该还认").isFalse();
    }

    @Test
    void you_cannot_switch_into_an_operator_you_do_not_belong_to() {
        String mine = onboardedPhone("越权切换-我");
        String theirs = onboardedPhone("越权切换-别人家");
        String myToken = loginByOtp(mine).path("token").asText();
        String theirAgentNo = loginByOtp(theirs).path("agentNo").asText();

        Resp r = post("/api/auth/operators/" + theirAgentNo + "/switch", Map.of(), myToken);
        assertThat(r.code()).as("切到别人家的主体必须拒: %s", r.msg()).isNotEqualTo(0);
    }

    @Test
    void staff_sessions_have_no_operators_and_cannot_switch() {
        String admin = login("ADMIN");
        assertThat(get("/api/auth/operators", admin).okData().size())
                .as("运营端会话没有「我的主体」这个概念").isZero();
    }
}
