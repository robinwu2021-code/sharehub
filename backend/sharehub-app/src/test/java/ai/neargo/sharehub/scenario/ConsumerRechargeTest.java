package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钱包充值要真的到账（C-WA-02）。
 *
 * <h2>此前这条路是断的，而且断得看不出来</h2>
 * 充值套餐表、充值单表、钱包、流水都建好了，但**没有任何地方会创建充值单**：
 * 运营端那份是只读列表，C 端的「充值」按钮打的是 {@code POST /mp/trade/pay}，
 * 而那个口在没有 {@code orderNo} 时直接抛「充值等无单支付待充值单流程接入」。
 * c-app 那边没有 catch，于是点下去<b>什么都不发生</b> —— 没提示、没报错，
 * 只有按钮转一下就恢复。于是 {@code usr_wallet} 里的钱只可能来自运营手工调账，
 * 「累计充值金额」这个经营指标恒为 0。
 *
 * <h2>为什么要断言「前端传金额不算数」</h2>
 * 充值接口只收 {@code packageNo}。哪天有人为了图方便改成接受 {@code payAmount}，
 * 「充 1 元到账 100」就是一次普通的改参数请求，而且账面完全自洽 ——
 * 充值单、流水、余额三处都对得上，对账查不出来。
 */
class ConsumerRechargeTest extends ApiTestSupport {

    private static String freshPhone() {
        return "+9715007" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
    }

    private String consumerToken(String phone) {
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    /** 本用例专用套餐：共享测试库是累积的，复用别人的套餐会让金额断言随别人的改动偶发红。 */
    private String freshPackage(String admin, String status, double pay, double gift) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "充值用例 " + ThreadLocalRandom.current().nextInt(100_000, 999_999));
        body.put("payAmount", pay);
        body.put("giftAmount", gift);
        body.put("currency", "AED");
        body.put("sortNo", 999);
        body.put("status", status);
        return post("/api/user/recharge-packages", body, admin).okData().path("packageNo").asText();
    }

    @Test
    @DisplayName("★★ 充值闭环：下单 → 桩通道即时支付 → 余额与赠额都到账，且流水能反查到单号")
    void recharge_credits_the_wallet_and_leaves_a_traceable_txn() {
        String admin = login("ADMIN");
        String packageNo = freshPackage(admin, "ENABLED", 50, 10);
        String token = consumerToken(freshPhone());

        JsonNode before = get("/mp/user/wallet", token).okData();
        double balance0 = before.path("balance").asDouble();
        double bonus0 = before.path("giftBalance").asDouble();

        JsonNode r = post("/mp/user/recharge", Map.of("packageNo", packageNo), token).okData();
        String rechargeNo = r.path("rechargeNo").asText();
        assertThat(rechargeNo).as("充值单号").startsWith("RCH");
        assertThat(r.path("status").asText()).as("桩通道即时成功 → PAID").isEqualTo("PAID");
        assertThat(r.path("payAmount").asDouble()).isEqualTo(50.0);
        assertThat(r.path("giftAmount").asDouble()).isEqualTo(10.0);
        assertThat(r.path("creditAmount").asDouble()).as("到账 = 本金 + 赠额").isEqualTo(60.0);

        // 接口自己的返回值只是「我说我入账了」；再查一次钱包才是库里的事实
        JsonNode after = get("/mp/user/wallet", token).okData();
        assertThat(after.path("balance").asDouble()).as("余额应当 +50").isEqualTo(balance0 + 50);
        assertThat(after.path("giftBalance").asDouble()).as("赠额应当 +10").isEqualTo(bonus0 + 10);

        // 流水必须能反查到充值单，否则对账出差额时线索就断在这里
        JsonNode txns = get("/mp/user/wallet/txns", token).okData().path("list");
        JsonNode principal = null;
        JsonNode bonus = null;
        for (JsonNode t : txns) {
            if (!rechargeNo.equals(t.path("bizNo").asText())) continue;
            if ("RECHARGE".equals(t.path("type").asText())) principal = t;
            if ("BONUS".equals(t.path("type").asText())) bonus = t;
        }
        assertThat(principal).as("本金流水（type=RECHARGE, bizNo=%s）", rechargeNo).isNotNull();
        assertThat(bonus).as("赠额流水（type=BONUS）—— 与本金分两条，否则再也分不清充了多少、送了多少")
                .isNotNull();
        assertThat(principal.path("amount").asDouble()).isEqualTo(50.0);
        assertThat(bonus.path("amount").asDouble()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("★★ 前端传的金额一概不算数——金额只认套餐")
    void amount_comes_from_the_package_never_from_the_request() {
        String admin = login("ADMIN");
        String packageNo = freshPackage(admin, "ENABLED", 20, 0);
        String token = consumerToken(freshPhone());

        Map<String, Object> tampered = new HashMap<>();
        tampered.put("packageNo", packageNo);
        tampered.put("payAmount", 1);        // 「我只付 1 块」
        tampered.put("creditAmount", 1000);  // 「但给我到账 1000」
        tampered.put("giftAmount", 999);

        JsonNode r = post("/mp/user/recharge", tampered, token).okData();
        assertThat(r.path("payAmount").asDouble()).as("实付只认套餐的 20").isEqualTo(20.0);
        assertThat(r.path("giftAmount").asDouble()).as("赠额只认套餐的 0").isEqualTo(0.0);
        assertThat(r.path("creditAmount").asDouble()).isEqualTo(20.0);
        assertThat(get("/mp/user/wallet", token).okData().path("balance").asDouble())
                .as("库里也只该多 20").isEqualTo(20.0);
    }

    @Test
    @DisplayName("停用/不存在的套餐 → 400，而不是 500 或静默成功")
    void unknown_or_disabled_package_is_refused() {
        String admin = login("ADMIN");
        String token = consumerToken(freshPhone());

        assertThat(post("/mp/user/recharge", Map.of("packageNo", "RP-NOPE-0000"), token).status)
                .as("不存在的套餐").isEqualTo(400);
        assertThat(post("/mp/user/recharge", Map.of(), token).status)
                .as("没选套餐").isEqualTo(400);

        String disabled = freshPackage(admin, "DISABLED", 30, 0);
        // 409 而不是 400：请求本身没毛病，是**套餐的状态**不允许 —— 与「套餐不存在」
        // （那才是入参错）分开。2026-09-25 随 i18n 那批一起改，此前只有 400 可用。
        assertThat(post("/mp/user/recharge", Map.of("packageNo", disabled), token).status)
                .as("已停用的套餐不该还能充 —— 停用就是为了停止售卖").isEqualTo(409);
        assertThat(get("/mp/user/wallet", token).okData().path("balance").asDouble())
                .as("被拒之后余额不该动").isEqualTo(0.0);
    }

    @Test
    @DisplayName("停用的套餐也不出现在 C 端可购列表里")
    void disabled_packages_are_not_offered() {
        String admin = login("ADMIN");
        String disabled = freshPackage(admin, "DISABLED", 30, 0);
        String token = consumerToken(freshPhone());

        for (JsonNode p : get("/mp/user/recharge-packages", token).okData()) {
            assertThat(p.path("packageNo").asText()).isNotEqualTo(disabled);
        }
    }
}
