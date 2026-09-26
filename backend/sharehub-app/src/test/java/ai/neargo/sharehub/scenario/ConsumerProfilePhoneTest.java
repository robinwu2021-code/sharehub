package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「我的资料」里的手机号**不能是身份哈希**。
 *
 * <h3>2026-09-27 H5 对接实测发现</h3>
 * 用 {@code +971501234567} 登录后，资料页显示的手机号是 {@code 056****7c12}。
 * 追下去：{@code UserQueryServiceImpl.phonesOf} 把 {@code usr_identity.provider_uid}
 * （**登录标识的 HMAC 十六进制**）当手机号做了掩码。那一列从来不是手机号。
 *
 * <p>影响面不止 C 端：运营端用户 / 风控 / 黑名单 / 白名单**四张表**的「手机」列，
 * 每个消费者都是这种十六进制尾巴。
 *
 * <h3>为什么一直没被发现</h3>
 * <b>掩码把它伪装得很像一个真手机号</b>。一个打了码的号，谁也不会去核对中间那几位 ——
 * 这正是这类缺陷的形状：它不报错、不空白、看起来完全正常。
 *
 * <p>根因是 {@code pb_pii} 分库从没建过（架构级 D4），所以明文**没有存放处**。
 * 既然没有来源，就该如实出空 —— 同一个出参里 {@code email} 早就是这么处理的
 * （「无列（pb_pii 未建），如实出 null」）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerProfilePhoneTest extends ApiTestSupport {

    @Test
    @DisplayName("资料里的 phone 不是十六进制哈希的掩码")
    void profilePhoneIsNotAMaskedHash() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        String got = get("/mp/user/profile", token).okData().path("phone").asText();

        assertThat(got)
                .as("""
                        没有明文来源就该出空。此前这里是 mask(provider_uid) ——
                        一个 HMAC 掩码出来长得像 056****7c12，看着完全像个手机号。""")
                .isEmpty();
    }

    @Test
    @DisplayName("掩码形状的回归钉子：一旦又出现 xxx****xxxx 就红")
    void nothingPhoneShapedComesBack() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        String got = get("/mp/user/profile", token).okData().path("phone").asText();

        // 单独写一条形状断言：将来若有人接上真明文，这条要跟着改（那时它才该是真号的掩码）；
        // 而在那之前，任何「看起来像掩码手机号」的东西都是那个哈希又回来了。
        assertThat(got)
                .as("出现 ***  就说明又在拿什么东西冒充手机号了")
                .doesNotContain("****");
    }
}
