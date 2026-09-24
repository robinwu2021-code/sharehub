package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取证：代理会话能看到别人的资金数据吗？
 *
 * <p>这是在代理端实名登录打通<b>之后</b>才需要问的问题 —— 在那之前没有任何代理能登录，
 * 缺口是潜在的。现在门开了，得看清门后是什么。
 *
 * <p>{@code DataScopeRegistration} 的类注释已写明 share_record / stl_settlement
 * 「待表建好后补注册」，而 stl_withdrawal / stl_payout_account 连提都没提。
 * 本测试<b>不假设结论</b>，直接调接口看回来多少行。
 */
@org.springframework.test.context.TestPropertySource(properties = {
        "sharehub.security.apply-rate-per-minute=10000",
})
class AgentDataScopeProbeTest extends ApiTestSupport {

    private static final String OTP = "000000";

    private static String randomPhone() {
        return "+97150" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_000_000));
    }

    /** 走完入驻拿一张真实的代理 token，并返回它的 agentNo。 */
    private String[] onboardedAgent() {
        String phone = randomPhone();
        post("/api/agent/apply/otp", Map.of("phone", phone), null).okData();
        Map<String, Object> b = new HashMap<>();
        b.put("phone", phone);
        b.put("otp", OTP);
        b.put("email", "t" + Math.abs(phone.hashCode()) + "@example.com");
        b.put("operatorName", "数据范围取证");
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/apply", b, null).okData().path("applyNo").asText();
        String admin = login("ADMIN");
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin).okData();

        post("/api/auth/otp", Map.of("phone", phone), null).okData();
        JsonNode r = post("/api/auth/login", Map.of("phone", phone, "otp", OTP), null).okData();
        return new String[] { r.path("token").asText(), r.path("agentNo").asText() };
    }

    /** 这个新代理名下**一条资金记录都没有** —— 所以任何非零结果都是别人的数据。 */
    @Test
    void a_brand_new_agent_should_not_see_other_peoples_money() {
        String[] agent = onboardedAgent();
        String token = agent[0], agentNo = agent[1];

        record Probe(String name, String path) { }
        var probes = new Probe[] {
                new Probe("提现单", "/api/trade/withdrawals?page=1&size=50"),
                new Probe("分润明细", "/api/trade/share-records?page=1&size=50"),
                new Probe("结算单", "/api/trade/settlements?page=1&size=50"),
                new Probe("收款账户", "/api/trade/payout-accounts?page=1&size=50"),
        };

        var leaks = new java.util.ArrayList<String>();
        for (Probe p : probes) {
            Resp r = get(p.path(), token);
            if (r.code() != 0) continue;                 // 无权限码 → 本就看不到，不是泄露
            long total = r.data().path("total").asLong();
            if (total > 0) leaks.add(p.name() + "=" + total + " 行");
        }

        assertThat(leaks)
                .as("新代理 %s 名下没有任何资金记录，却查到了这些 —— 全部是别人的数据", agentNo)
                .isEmpty();
    }

    /**
     * 反向那一半：**别把代理自己的数据也关掉**。
     *
     * <p>{@code DataScopeHandler} 是 fail-closed —— 维度在锚点里找不到列时生成 {@code 1=0}。
     * 所以「查不到别人的」这条断言，被一个把所有人都拒光的实现同样能满足。
     * 不配这条正向用例的话，锁死与修好在测试上长得一模一样。
     */
    @Test
    void an_agent_still_sees_its_own_rows() {
        String[] agent = onboardedAgent();
        String token = agent[0], agentNo = agent[1];
        String admin = login("ADMIN");

        // 给这个代理造一条属于他自己的收款账户
        Map<String, Object> acct = new HashMap<>();
        acct.put("payeeType", "AGENT");
        acct.put("payeeNo", agentNo);
        acct.put("bankCode", "ENBD");
        acct.put("accountName", "自有数据可见性测试");
        acct.put("accountMasked", "AE07033123456789012****");
        acct.put("currency", "AED");
        post("/api/trade/payout-accounts", acct, admin).okData();

        JsonNode mine = get("/api/trade/payout-accounts?page=1&size=50", token).okData();
        assertThat(mine.path("total").asLong())
                .as("代理必须看得到自己的收款账户 —— 否则「你还不能收款」会对所有人恒显示")
                .isEqualTo(1);
        assertThat(mine.path("list").get(0).path("payeeNo").asText()).isEqualTo(agentNo);
    }

    /** 运营侧是 ALL 范围：注册锚点不该把他们也收窄。 */
    @Test
    void staff_still_sees_everything() {
        String admin = login("ADMIN");
        assertThat(get("/api/trade/payout-accounts?page=1&size=50", admin).okData().path("total").asLong())
                .as("ADMIN 是 ALL 范围，注册锚点后不该看不到东西").isGreaterThan(1);
        assertThat(get("/api/trade/share-records?page=1&size=50", admin).okData().path("total").asLong())
                .isGreaterThan(1);
    }
}
