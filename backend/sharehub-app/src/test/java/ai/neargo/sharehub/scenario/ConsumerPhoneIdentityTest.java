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
 * 手机号身份里的区号（2026-09-27 拍板）。
 *
 * <h3>此前</h3>
 * 端上每次都发 {@code countryCode}，后端一直没读：区号只来自 {@code phone} 自带或 {@code default-calling-code}。
 * 于是 <b>+971 501234567 与 +966 501234567 是同一个账号</b>，钱包、订单、券、押金全部共用。
 * 本类原先钉住的就是这个现状，并注明「区号参与派生后这条会红，届时更新」。
 *
 * <h3>为什么当时没修</h3>
 * 让区号参与派生会破掉惰性重键的唯一性：历史行的旧 uid 是 {@code sha256(端上原样发的本地号)}，
 * 两个区号的同号会争抢同一条历史行。需要先回答「撞号的历史行归谁」。
 *
 * <h3>拍板：历史行一律归 +971</h3>
 * 此前只在阿联酋运营，历史账号实际都是 +971 用户。所以：
 * <ol>
 *   <li>区号取值确定：{@code phone} 自带 {@code +} 以它为准 → 否则 {@code countryCode} → 都没有才用默认区号；</li>
 *   <li>只有归一后落在默认区号（971）的号码才去认领旧行 —— +966 的同号从此是新账号，抢不走历史行。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerPhoneIdentityTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("同一本地号：+971 与 +966 是两个账号；不传区号等同默认区号 +971")
    void countryCodeTakesPartInIdentity() {
        String local = "5" + (System.nanoTime() % 100000000L);

        String ae = loginWith("+971", local);
        String sa = loginWith("+966", local);
        assertThat(sa).as("区号不同就是不同的人 —— 此前两人共用钱包、订单、券、押金").isNotEqualTo(ae);
        assertThat(loginWith(null, local)).as("不传区号 = 默认区号 +971").isEqualTo(ae);
    }

    @Test
    @DisplayName("phone 自带 + 时以它为准，countryCode 不再覆盖（两个都填且不一致时有确定答案）")
    void plusInPhoneWinsOverCountryCode() {
        String local = "5" + (System.nanoTime() % 100000000L);
        String ae = loginWith("+971", local);
        assertThat(loginWith("+966", "+971" + local)).isEqualTo(ae);
    }

    @Test
    @DisplayName("历史行归 +971：+966 的同号认领不了，+971 登录仍能认领回老账号")
    void legacyRowBelongsToDefaultMarket() {
        String local = "5" + (System.nanoTime() % 100000000L);
        // 旧算法时期端上原样发本地号：uid = sha256("501234567") 前 32 位
        String cUserNo = "CU" + Long.toHexString(System.nanoTime()) + "LEG";
        jdbc.update("insert into usr_user (c_user_no, tenant_id, credit_score, status) values (?, 'MAIN', 600, 'ACTIVE')",
                cUserNo);
        String legacyUid = bareSha256(local);
        jdbc.update("insert into usr_identity (c_user_no, tenant_id, provider, provider_uid, union_key) "
                + "values (?, 'MAIN', 'PHONE', ?, ?)", cUserNo, legacyUid, "PHONE:" + legacyUid);

        assertThat(loginWith("+966", local))
                .as("+966 的同号先来也不能把 +971 用户的历史账号搬走").isNotEqualTo(cUserNo);
        assertThat(loginWith("+971", local))
                .as("+971 用户登录照旧认领自己的历史账号（钱包/订单/券都在）").isEqualTo(cUserNo);
    }

    /** 用 (区号, 本地号) 走一次 OTP 登录，返回落到的 c_user_no。区号为 null 表示不传。 */
    private String loginWith(String countryCode, String phone) {
        Map<String, Object> otp = new java.util.HashMap<>(Map.of("phone", phone));
        Map<String, Object> login = new java.util.HashMap<>(Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"));
        if (countryCode != null) {
            otp.put("countryCode", countryCode);
            login.put("countryCode", countryCode);
        }
        post("/mp/auth/otp", otp, null).okData();
        return post("/mp/auth/login", login, null).okData().path("cUserNo").asText();
    }

    private static String bareSha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
