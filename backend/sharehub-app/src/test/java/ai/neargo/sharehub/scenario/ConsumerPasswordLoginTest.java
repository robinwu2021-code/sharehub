package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 端口令登录（`cred_credential` 的 `realm=CONSUMER`）。
 *
 * <h3>此前是什么状态</h3>
 * c-app 的登录页画着「密码」页签、发的就是 `grantType:"password"`，而后端**没有这个策略**
 * —— 路径与形状都对，对齐卡口全绿，用户点下去吃 400。忘记密码与改密两处同样：
 * 前者后端如实 400（理由写「凭据库 pb_auth 未建」），后者 c-app 用 `setTimeout` 假装成功、
 * 根本没调后端。
 *
 * <h3>不新建 pb_auth 分库</h3>
 * `cred_credential` 从建起来就按 realm 参数化（`uk_realm_subject`，表注释「员工与代理共用」），
 * 而「先在 pb_core 起一张平行凭据表、auth-core 落地时整表迁移」是已记录的决定。
 * 本能力只是多一个 realm 的行 —— 不是多一张表，所以不增加那次迁移的工作量。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerPasswordLoginTest extends ApiTestSupport {

    private static final String PWD = "Str0ngPwd!23";

    @Autowired
    JdbcTemplate jdbc;

    /** 口令登录默认是关的（V126），本类要开着才能测。 */
    @BeforeEach
    void enablePassword() {
        jdbc.update("update sys_login_setting set password_enabled=1 where tenant_id='MAIN' and country='*'");
    }

    @AfterEach
    void restore() {
        jdbc.update("update sys_login_setting set otp_enabled=1, password_enabled=0 "
                + "where tenant_id='MAIN' and country='*'");
    }

    /** 用 OTP 建一个号并设好口令，返回 (phone, cUserNo)。 */
    private String[] userWithPassword() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        var reg = post("/mp/auth/register", map(
                "phone", phone, "otp", "000000", "nickname", "PwdUser", "password", PWD), null).okData();
        assertThat(reg.path("passwordStored").asBoolean())
                .as("注册带了 password 就必须如实回 true —— 此前这里是写死的 false")
                .isTrue();
        return new String[]{phone, reg.path("cUserNo").asText()};
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("口令登录落到与 OTP 登录同一个账号（不是新建一个）")
    void passwordLoginLandsOnSameAccount() {
        String[] u = userWithPassword();

        var d = post("/mp/auth/login",
                map("grantType", "password", "phone", u[0], "password", PWD), null).okData();

        assertThat(d.path("cUserNo").asText())
                .as("口令登录**不建户**：查不到身份就该拒，不能凭一个随便填的口令建出空账号")
                .isEqualTo(u[1]);
        assertThat(d.path("isNew").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("口令错 → 400，且文案与「号不存在」相同（不做手机号探测器）")
    void wrongPasswordAndUnknownPhoneLookIdentical() {
        String[] u = userWithPassword();

        var wrong = post("/mp/auth/login",
                map("grantType", "password", "phone", u[0], "password", "WrongPwd!23"), null);
        var absent = post("/mp/auth/login",
                map("grantType", "password", "phone", "+971509999999", "password", PWD), null);

        assertThat(wrong.status).isEqualTo(400);
        assertThat(absent.status).isEqualTo(400);
        assertThat(absent.msg())
                .as("两种失败必须同一句 —— 区分开来就是一台「这个号注册过没有」的探测器")
                .isEqualTo(wrong.msg());
    }

    @Test
    @DisplayName("没设过口令的号：也是同一句，不泄露「他只是没设密码」")
    void accountWithoutPasswordLooksTheSame() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        post("/mp/auth/login", map("grantType", "phone_otp", "phone", phone, "otp", "000000"), null).okData();

        var resp = post("/mp/auth/login", map("grantType", "password", "phone", phone, "password", PWD), null);
        assertThat(resp.status).isEqualTo(400);
    }

    @Test
    @DisplayName("连续失败 5 次后锁定 —— 锁定期内口令正确也拒")
    void lockoutRejectsEvenCorrectPassword() {
        String[] u = userWithPassword();
        for (int i = 0; i < 5; i++) {
            post("/mp/auth/login", map("grantType", "password", "phone", u[0], "password", "Wrong!" + i), null);
        }
        var correct = post("/mp/auth/login",
                map("grantType", "password", "phone", u[0], "password", PWD), null);
        assertThat(correct.status)
                .as("锁定期内放行的话，爆破者试到正确口令那一刻锁就白上了")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("忘记密码：验过 OTP 才能改，新口令生效、旧口令立刻失效")
    void resetByOtpSwapsThePassword() {
        String[] u = userWithPassword();
        String newPwd = "An0therPwd!45";

        post("/mp/auth/otp", Map.of("phone", u[0], "scene", "reset"), null).okData();
        post("/mp/auth/password/reset",
                map("phone", u[0], "otp", "000000", "password", newPwd), null).okData();

        assertThat(post("/mp/auth/login",
                map("grantType", "password", "phone", u[0], "password", newPwd), null)
                .okData().path("cUserNo").asText()).isEqualTo(u[1]);
        assertThat(post("/mp/auth/login",
                map("grantType", "password", "phone", u[0], "password", PWD), null).status)
                .as("旧口令必须立刻失效").isEqualTo(400);
    }

    @Test
    @DisplayName("忘记密码：OTP 不对就改不动（顺序反了就是任何人能改任意号的口令）")
    void resetRequiresValidOtp() {
        String[] u = userWithPassword();
        var resp = post("/mp/auth/password/reset",
                map("phone", u[0], "otp", "111111", "password", "Hacked!123"), null);
        assertThat(resp.status).isEqualTo(400);
        // 旧口令仍然好使 → 说明真的没改
        assertThat(post("/mp/auth/login",
                map("grantType", "password", "phone", u[0], "password", PWD), null).status).isEqualTo(200);
    }

    @Test
    @DisplayName("改密：旧口令错则拒；正确则换成新的")
    void changePasswordChecksOldOne() {
        String[] u = userWithPassword();
        String token = post("/mp/auth/login",
                map("grantType", "password", "phone", u[0], "password", PWD), null)
                .okData().path("token").asText();
        String newPwd = "Thir4Pwd!678";

        assertThat(post("/mp/user/password",
                map("oldPassword", "NotIt!234", "newPassword", newPwd), token).status)
                .as("旧口令错必须拒 —— c-app 此前用 setTimeout 假装成功，这条从来没被测过")
                .isEqualTo(400);

        post("/mp/user/password", map("oldPassword", PWD, "newPassword", newPwd), token).okData();
        assertThat(post("/mp/auth/login",
                map("grantType", "password", "phone", u[0], "password", newPwd), null).status).isEqualTo(200);
    }

    @Test
    @DisplayName("改密要求已登录：无 token 不是 200")
    void changePasswordNeedsSession() {
        assertThat(post("/mp/user/password",
                map("oldPassword", PWD, "newPassword", "Whatever!99"), null).status)
                .as("这也是它放在 /mp/user/** 而不是 permitAll 的 /mp/auth/** 的原因")
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("开关关着时：设/重设/改密全拒，不让用户设一个永远用不上的口令")
    void allPasswordActionsRespectTheSwitch() {
        String[] u = userWithPassword();
        jdbc.update("update sys_login_setting set password_enabled=0 where tenant_id='MAIN' and country='*'");

        assertThat(post("/mp/auth/login",
                map("grantType", "password", "phone", u[0], "password", PWD), null).status).isEqualTo(400);
        assertThat(post("/mp/auth/password/reset",
                map("phone", u[0], "otp", "000000", "password", "Nope!2345"), null).status).isEqualTo(400);
    }

    @Test
    @DisplayName("口令太短照拒（最短长度与员工侧同一个常量）")
    void tooShortRejected() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        var resp = post("/mp/auth/register", map(
                "phone", phone, "otp", "000000", "nickname", "Shorty", "password", "abc"), null);
        assertThat(resp.status).isEqualTo(400);
    }
}
