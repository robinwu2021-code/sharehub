package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录方式开关（需求 OPS-SYS-07）：`sys_login_setting` 第一次真的生效。
 *
 * <h3>它此前是什么状态</h3>
 * 表自 V5 就存在，`otp/password/apple/google` 四个开关列齐备，**全仓没有任何一处读它** ——
 * 运营台那个「密码登录」开关拨了等于没拨。而 c-app 的登录页无条件画出「密码」页签，
 * 后端连 `password` 这个 grantType 都没有，于是用户点下去吃一个 400。
 * <b>一个拨了不生效的开关比没有开关更糟</b>：它让人以为这件事已经被管起来了。
 *
 * <h3>两条都要有，缺一条等于没做</h3>
 * <ul>
 *   <li>`GET /mp/auth/methods` —— 端上据此隐入口（**建议**）；</li>
 *   <li>`ConsumerAuthService` 的门禁 —— 端上隐了，客户端照样能直接发那个 grantType（**保护**）。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginMethodSwitchTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    /** 每条用例后把默认行复位 —— test_sharehub 是共享库，留下改动会让别的用例莫名变红。 */
    @AfterEach
    void restoreDefaults() {
        jdbc.update("update sys_login_setting set otp_enabled=1, password_enabled=0, "
                + "apple_enabled=0, google_enabled=0 where tenant_id='MAIN' and country='*'");
        jdbc.update("delete from sys_login_setting where tenant_id='MAIN' and country='ZZ'");
    }

    @Test
    @DisplayName("方式下发：默认行 otp 开、其余关（V126 的取值）")
    void methodsReflectDefaultRow() {
        var d = get("/mp/auth/methods", null).okData();
        assertThat(d.path("country").asText()).isEqualTo("*");
        assertThat(d.path("otp").asBoolean()).isTrue();
        assertThat(d.path("password").asBoolean())
                .as("CONSUMER realm 的凭据还没建，开着就是让用户撞「能点但不通」")
                .isFalse();
        assertThat(d.path("otpExpireSec").asInt()).isPositive();
    }

    @Test
    @DisplayName("按国家取：命中国家行；查不到的国家回落默认行")
    void methodsFallBackToDefaultRow() {
        jdbc.update("insert into sys_login_setting (tenant_id,country,country_name,"
                + "otp_enabled,password_enabled,apple_enabled,google_enabled,"
                + "otp_expire_sec,otp_daily_limit,force_real_name) "
                + "values ('MAIN','ZZ','测试国',1,1,0,0,300,10,0)");

        assertThat(get("/mp/auth/methods?country=ZZ", null).okData().path("password").asBoolean())
                .as("命中国家行").isTrue();
        assertThat(get("/mp/auth/methods?country=QQ", null).okData().path("password").asBoolean())
                .as("没有 QQ 行 → 回落 '*'，而默认行 password 是关的").isFalse();
    }

    /**
     * 这条是本次的**保护**，不是下发。
     *
     * <p>只靠端上隐入口是不够的：入口隐了，客户端照样能直接把 grantType 发过来。
     * 开关关着而请求仍然成立，等于这个开关只是界面上的装饰。
     */
    @Test
    @DisplayName("服务端强制：开关关着时，端上直接发该 grantType 也要被拒")
    void serverRejectsDisabledMethodEvenIfClientAsks() {
        // otp 关掉（password 仍关）—— 用 otp 验证门禁，因为它是唯一走得通的方式
        jdbc.update("update sys_login_setting set otp_enabled=0, password_enabled=1 "
                + "where tenant_id='MAIN' and country='*'");

        String local = "50" + (System.nanoTime() % 10000000L);
        var resp = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", "+971" + local, "otp", "000000"), null);

        assertThat(resp.status)
                .as("otp_enabled=0 时 phone_otp 必须被拒；放行就说明门禁只是装饰")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("开关开着时照常能登（门禁不该顺手挡住正常路径）")
    void enabledMethodStillWorks() {
        String local = "50" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", "+971" + local), null).okData();
        var d = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", "+971" + local, "otp", "000000"),
                null).okData();
        assertThat(d.path("cUserNo").asText()).isNotBlank();
    }

    /**
     * ⚠️ 这条测的**不是门禁**，别把它当门禁的覆盖。
     *
     * <p>`strategies.get(grantType) == null` 在门禁之前就抛了，所以未知方式走不到
     * `allows()` 的 `default -> false`。两道保护叠在一起、外面那道遮住里面那道 ——
     * 里面那道要单测直打，见 {@code LoginMethodAllowsTest}。
     * 保留本条是因为「未知方式必须 400」本身值得钉住。
     */
    @Test
    @DisplayName("未知 grantType 一律 400（由策略查找拦下，不是由开关）")
    void unknownGrantTypeRejectedByStrategyLookup() {
        var resp = post("/mp/auth/login",
                Map.of("grantType", "telepathy", "phone", "+971500000001"), null);
        assertThat(resp.status).isEqualTo(400);
    }
}
