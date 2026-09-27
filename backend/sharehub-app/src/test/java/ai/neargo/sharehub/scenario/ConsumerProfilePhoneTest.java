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
 * 修复第一步是如实出空（「我的」页因此只剩一个空卡）。
 *
 * <h3>2026-09-27 第二步：登录时存一份真号的掩码</h3>
 * 登录那一刻手里有归一化后的明文（{@code ResolvedIdentity.phone}），只是一直没人存。
 * 现在存进 {@code usr_identity.display_mask}：<b>只存掩码、不存可逆密文</b> ——
 * 显示用掩码就够了，而可逆存储要引入密钥管理（V130 正是出于这个理由删掉了代理侧的 *_enc）。
 * 老用户下次登录时补上。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerProfilePhoneTest extends ApiTestSupport {

    @Test
    @DisplayName("资料里的 phone 是登录所用号码的掩码，不是十六进制哈希的掩码")
    void profilePhoneIsTheMaskOfTheRealNumber() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        String got = get("/mp/user/profile", token).okData().path("phone").asText();

        String digits = phone.replaceAll("\\D", "");
        assertThat(got)
                .as("登录时存下的是真号的掩码：保留前 3 后 4")
                .contains("****")
                .startsWith(digits.substring(0, 3))
                .endsWith(digits.substring(digits.length() - 4));
    }

    @Test
    @DisplayName("回归钉子：掩码的尾巴必须是数字 —— 出现十六进制说明又在拿哈希冒充手机号")
    void maskIsNeverDerivedFromTheHash() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        String got = get("/mp/user/profile", token).okData().path("phone").asText();

        // 此前的 mask(provider_uid) 长得像 056****7c12 —— 尾巴是十六进制
        assertThat(got)
                .as("掩码两头只能是数字；出现 a-f 就是 HMAC 的尾巴")
                .matches("\\d{3}\\*{4}\\d{4}");
    }
}
