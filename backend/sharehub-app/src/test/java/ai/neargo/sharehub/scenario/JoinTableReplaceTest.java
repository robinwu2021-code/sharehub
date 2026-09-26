package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「多值拆表」的 CSV → 关联表 全量重写：**取消再加回来会撞唯一键**。
 *
 * <p>这两张关联表（{@code usr_recharge_pkg_market} / {@code pay_channel_scope}）
 * 都继承 {@code BaseEntity} —— 于是 MyBatis-Plus 的 {@code delete()} 是**逻辑删除**
 * （{@code SET deleted = 1}），而它们的唯一键里**没有 deleted}：
 * <pre>
 *   UNIQUE KEY uk_rpkg_market       (package_no, country_code)
 *   UNIQUE KEY uk_pay_channel_scope (channel_code, scope_type, scope_value)
 * </pre>
 * 「先删后插」的删没真删，插又撞在软删那行上 →
 * {@code Duplicate entry '...' for key 'uk_...'} → 500。
 *
 * <p>症状很具体：<b>某个市场/国家取消过一次，以后就再也加不回来了</b>，
 * 保存时报「服务器错误」。第一次配、以及只增不减，都正常 ——
 * 所以它躲过了所有只测「配置一次」的路径。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JoinTableReplaceTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    final List<String> pkgs = new ArrayList<>();
    final List<String> channels = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String p : pkgs) {
            jdbc.update("DELETE FROM usr_recharge_pkg_market WHERE package_no=?", p);
            jdbc.update("DELETE FROM usr_recharge_pkg WHERE package_no=?", p);
        }
        for (String c : channels) {
            jdbc.update("DELETE FROM pay_channel_scope WHERE channel_code=?", c);
            jdbc.update("DELETE FROM pay_channel WHERE channel_code=?", c);
        }
    }

    @Test
    @DisplayName("★★ 充值套餐：取消一个市场再加回来，不该 500")
    void rechargeMarketCanComeBack() {
        String admin = login("ADMIN");
        String no = "PKG_JT" + System.nanoTime();
        pkgs.add(no);

        savePkg(admin, no, "AE,SA");
        assertThat(marketsOf(admin, no)).isEqualTo("AE,SA");

        savePkg(admin, no, "AE");                 // 取消 SA
        assertThat(marketsOf(admin, no)).isEqualTo("AE");

        savePkg(admin, no, "AE,SA");               // 再加回来 —— 此前这一步 500
        assertThat(marketsOf(admin, no)).as("加回来的市场必须真的回来").isEqualTo("AE,SA");
    }

    @Test
    @DisplayName("★★ 支付渠道：取消一个国家再加回来，不该 500")
    void paymentScopeCanComeBack() {
        String admin = login("ADMIN");
        String code = "JTCH" + (System.nanoTime() % 100000);
        channels.add(code);

        saveChannel(admin, code, "AE,SA");
        assertThat(countriesOf(admin, code)).isEqualTo("AE,SA");

        saveChannel(admin, code, "AE");
        assertThat(countriesOf(admin, code)).isEqualTo("AE");

        saveChannel(admin, code, "AE,SA");
        assertThat(countriesOf(admin, code)).as("加回来的国家必须真的回来").isEqualTo("AE,SA");
    }

    // —— 夹具 ——

    private void savePkg(String admin, String no, String markets) {
        Map<String, Object> m = new HashMap<>();
        m.put("packageNo", no);
        m.put("name", "关联表测试包");
        m.put("payAmount", 50);
        m.put("giftAmount", 5);
        m.put("currency", "AED");
        m.put("validDays", 180);
        m.put("markets", markets);
        post("/api/user/recharge-packages/" + no, m, admin).okData();
    }

    private String marketsOf(String admin, String no) {
        JsonNode row = findInPages("/api/user/recharge-packages?showArchived=true", "packageNo", no, admin);
        return row.path("markets").asText();
    }

    private void saveChannel(String admin, String code, String countries) {
        Map<String, Object> m = new HashMap<>();
        m.put("channelCode", code);
        m.put("channelName", "关联表测试渠道");
        m.put("mode", "DIRECT");
        m.put("status", "DISABLED");
        m.put("countries", countries);
        m.put("currencies", "AED");
        m.put("capabilities", "支付");
        post("/api/platform/payment-channels/" + code, m, admin).okData();
    }

    private String countriesOf(String admin, String code) {
        JsonNode row = findInPages("/api/platform/payment-channels", "channelCode", code, admin);
        return row.path("countries").asText();
    }
}
