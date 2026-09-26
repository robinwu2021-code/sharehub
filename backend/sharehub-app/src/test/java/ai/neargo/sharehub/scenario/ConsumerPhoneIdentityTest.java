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
 * 手机号身份：{@code countryCode} 一直在发，一直没被用。
 *
 * <h3>现状（本类钉住的就是这个现状，不是期望）</h3>
 * c-app 的每一次认证调用都是 {@code {countryCode: "+971", phone: "501234567"}} 两个字段分开发，
 * 而后端只拿 {@code phone} 当键：
 * <ul>
 *   <li>{@code OtpService} 的码表按 {@code phone} 索引；</li>
 *   <li>{@code PhoneOtpStrategy} 的 {@code uid = sha256(phone)} 也按它派生。</li>
 * </ul>
 * 于是 <b>+971 501234567 与 +966 501234567 是同一个 OTP、同一个账号</b> ——
 * 钱包、订单、券、押金全部共用。而这个产品的 {@code md_market_country} 表和充值套餐的
 * 「适用市场」CSV 都是为多市场（AE/SA）建的。
 *
 * <p>{@code PhoneOtpStrategy} 里那句 {@code // 生产：E.164 归一} 是<b>没有实现的意图</b>
 * —— 实际只有 {@code .trim()}。
 *
 * <h3>为什么这里只钉住现状而不修</h3>
 * 修法是把 {@code uid} 改成按 E.164 派生，而 {@code uid} 就是账号身份 ——
 * 改它等于给**存量账号改身份**（老 uid 的行从此查不到）。这是要先拍板的迁移
 * （惰性重键？双读回落？停机重算？），不是一次重构能顺手做掉的。
 *
 * <p><b>本类的作用是让这件事改不动就一直显形</b>：等身份改成按 E.164 派生之后，
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
                        这不是期望行为，是被钉住的现状：uid = sha256(phone)，而 phone 不含区号。
                        修它要给存量账号改身份，属于待拍板的迁移（见 known-map-request-bodies.txt）。
                        **本条变红 = 身份已改好**，请连同台账那一条一起更新。""")
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
