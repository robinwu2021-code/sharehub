package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手机号身份：{@code countryCode} 一直在发，一直没被用（**本类钉住的是这个现状，不是期望**）。
 *
 * <h3>已经修掉的部分（2026-09-26，见 {@link ConsumerPhoneNormalizationTest}）</h3>
 * {@code PhoneOtpStrategy} 此前那句 {@code // 生产：E.164 归一} 是没有实现的意图
 * （实际只有 {@code .trim()}），且 uid 是裸 {@code sha256}。现已改用公共的
 * {@code IdentifierNormalizer} + {@code IdentifierHasher}，与员工侧同一个方法（ADR-030 §4.1）——
 * 同一个号的不同写法不再是不同账号，uid 也不再是可离线穷举的裸哈希。存量账号靠惰性重键认领。
 *
 * <h3>还剩下的就是本类这一条</h3>
 * 区号由 {@code phone} 自带或由 {@code default-calling-code} 补全，端上那个 {@code countryCode}
 * 依然没人读。于是 <b>+971 501234567 与 +966 501234567 仍是同一个账号</b> ——
 * 钱包、订单、券、押金全部共用。而这个产品的 {@code md_market_country} 表和充值套餐的
 * 「适用市场」CSV 都是为多市场（AE/SA）建的。
 *
 * <h3>为什么这一条没跟着一起修</h3>
 * 让 {@code countryCode} 参与派生就<b>破掉了惰性重键的唯一性</b>：两个区号的旧 uid 是同一个
 * {@code sha256("501234567")}，两个人会争抢同一条历史行，把账号在彼此之间来回搬 ——
 * 比现在的共用更糟。所以它需要先回答一个只有业务能答的问题：
 * <b>存量里真有跨市场撞号的账号时，那条历史行归谁？</b>
 *
 * <p><b>本类的作用是让这件事改不动就一直显形</b>：等区号参与派生之后，
 * 下面这条会红，而那正是提醒「该来更新这个用例、并把台账里那一条删掉」的时刻。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerPhoneIdentityTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("⚠️ 现状：同号不同区号解析成同一个账号（修好后本条会红——那时请更新它）")
    void sameLocalNumberAcrossCountriesIsOneAccount() {
        String local = "5" + (System.nanoTime() % 100000000L);

        String ae = loginWith("+971", local);
        String sa = loginWith("+966", local);

        assertThat(sa)
                .as("""
                        两个区号登录出的是同一个 c_user_no —— countryCode 没参与身份派生。
                        这不是期望行为，是被钉住的现状：区号来自 default-calling-code，不是端上那个字段。
                        （归一与 pepper 已于 9-26 修好，见 ConsumerPhoneNormalizationTest；剩下的就是这一条。）
                        让它参与派生会破掉惰性重键的唯一性，需要先拍板「撞号的历史行归谁」。
                        **本条变红 = 区号已参与身份**，请连同台账那一条一起更新。""")
                .isEqualTo(ae);
    }

    /** 用 (区号, 本地号) 走一次 OTP 登录，返回落到的 c_user_no。 */
    private String loginWith(String countryCode, String local) {
        post("/mp/auth/otp", Map.of("countryCode", countryCode, "phone", local), null).okData();
        return post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "countryCode", countryCode, "phone", local, "otp", "000000"),
                null).okData().path("cUserNo").asText();
    }
}
