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
 * C 端手机号身份：**同一个号的不同写法必须是同一个账号**。
 *
 * <h3>修的是什么（2026-09-26）</h3>
 * {@code PhoneOtpStrategy} 此前自带一套 {@code sha256(phone.trim())}，与仓库里既有的
 * {@code IdentifierNormalizer} + {@code IdentifierHasher} 并行存在 —— 而代理侧
 * （{@code AgentLoginServiceImpl}）用的是后者。于是 C 端同时踩了那两个类 javadoc 里
 * 明确写着要避免的两件事：
 * <ol>
 *   <li><b>不归一</b>：{@code 0501234567} / {@code +971501234567} / {@code 00971501234567}
 *       算出三个 uid → 三个账号。失败形态是「注册能成、登录查不到」，<b>不报任何错</b>；</li>
 *   <li><b>裸哈希</b>：手机号取值空间穷举得完，无 pepper 等于明文存库 ——
 *       而 pepper 在配置里是强制项（不配起不来），员工侧一直在用。</li>
 * </ol>
 *
 * <h3>还没修的是 {@code countryCode}</h3>
 * 本地号补的是 {@code default-calling-code}，**不是**端上传的 {@code countryCode} ——
 * 所以 +971 与 +966 的同号仍是一个账号，见 {@link ConsumerPhoneIdentityTest}。
 * 不顺手改掉的原因在那个类里：让 countryCode 参与派生会让惰性重键失去唯一性
 * （两个区号的旧 uid 是同一个值，会互相抢同一条历史行）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerPhoneNormalizationTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("同一个号的三种写法 → 同一个账号（此前是三个）")
    void writeVariantsResolveToOneAccount() {
        String local = "50" + (System.nanoTime() % 10000000L);

        String a = loginAs("0" + local);            // 本地写法带前导 0
        String b = loginAs("+971" + local);         // 国际 +
        String c = loginAs("00971 " + local);       // 国际 00 + 空格

        assertThat(b).as("『+971…』与『0…』必须落同一个账号 —— 否则用户换个填法就丢了钱包与订单").isEqualTo(a);
        assertThat(c).as("『00971…』是同一个号的第三种写法").isEqualTo(a);
    }

    @Test
    @DisplayName("uid 是加 pepper 的 HMAC，不是裸 sha256（裸哈希等于明文存手机号）")
    void uidIsPepperedNotBareSha256() {
        String local = "50" + (System.nanoTime() % 10000000L);
        String cUserNo = loginAs("+971" + local);

        String uid = jdbc.queryForObject(
                "select provider_uid from usr_identity where c_user_no=? and provider='PHONE'",
                String.class, cUserNo);

        assertThat(uid).as("HMAC-SHA256 全长 64 位十六进制").hasSize(64).matches("[0-9a-f]{64}");
        assertThat(uid)
                .as("等于裸 sha256 前 32 位就说明 pepper 没生效 —— 拿到库的人可离线穷举出全部手机号")
                .isNotEqualTo(bareSha256("971" + local));
    }

    /**
     * 惰性重键：旧算法写下的那条 {@code usr_identity} 必须被<b>认领</b>，不能被绕过去新建账号。
     *
     * <p>这是换 uid 算法唯一可能悄悄造成的损失：老用户再登录时查不到自己，
     * 于是拿到一个空账号 —— 钱包余额、进行中的订单、未用的券全部「消失」，而接口一路 200。
     */
    @Test
    @DisplayName("存量账号：旧 uid 的行被就地重键，不是新建一个空账号")
    void legacyRowIsRekeyedNotOrphaned() {
        String local = "50" + (System.nanoTime() % 10000000L);
        String raw = "+971" + local;

        // 造一条「旧算法时期」的身份行：uid = sha256(原始输入).substring(0,32)，且挂在一个已有用户上
        String cUserNo = "CU" + Long.toHexString(System.nanoTime()) + "LEG";
        jdbc.update("insert into usr_user (c_user_no, tenant_id, credit_score, status) values (?, 'MAIN', 600, 'ACTIVE')",
                cUserNo);
        String legacyUid = bareSha256(raw);
        jdbc.update("insert into usr_identity (c_user_no, tenant_id, provider, provider_uid, union_key) "
                + "values (?, 'MAIN', 'PHONE', ?, ?)", cUserNo, legacyUid, "PHONE:" + legacyUid);

        String landed = loginAs(raw);

        assertThat(landed)
                .as("登录必须回到那个老账号 —— 新建一个的话，用户的钱包/订单/券就都『不见了』，而接口一路 200")
                .isEqualTo(cUserNo);
        Integer stillLegacy = jdbc.queryForObject(
                "select count(*) from usr_identity where provider_uid=?", Integer.class, legacyUid);
        assertThat(stillLegacy).as("旧 uid 应已被改写成新值，不能两行并存（否则下次又是两个账号）").isZero();
    }

    private String loginAs(String phone) {
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        return post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"),
                null).okData().path("cUserNo").asText();
    }

    /** 旧算法，只为构造历史数据与做负对照；生产代码里已不再产出这个值。 */
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
