package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人工客服会话的 C 端一侧（2026-09-28）。
 *
 * <p>在此之前 {@code /mp} 下**没有任何会话端点**，c-app 也没有会话页：
 * 用户被转人工之后，进度页永远停在「已转人工客服，正在排队处理」——
 * 客服回了什么他看不到，也没法追问。而「转人工」是分流的兜底方向，
 * 也就是最常走的那一条（问题字典只有两条，其余全兜底）。
 *
 * <p>本类钉住这条闭环：报障转人工 → 用户看得到会话与消息 → 客服回复用户看得到 →
 * 用户能接着说；以及一条不能松的边：**别人的会话查不到、发不了**。
 */
class ConsumerCsSessionTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("转人工之后：用户看得到会话、看得到客服回复、能接着说")
    void theConversationIsVisibleAndTwoWay() {
        String token = consumerToken();
        String issue = "宝一直弹不出来 " + System.nanoTime();
        JsonNode report = post("/mp/user/report", Map.of("issue", issue, "channel", "APP"), token).okData();
        assertThat(report.path("suggestedAction").asText()).as("前置：兜底转人工").isEqualTo("TO_CS");

        JsonNode session = get("/mp/user/cs-session", token).okData();
        String sessionNo = session.path("sessionNo").asText();
        assertThat(sessionNo).as("用户要看得到自己那个会话 —— 看不到就等于没有人在处理").isNotBlank();

        assertThat(texts(sessionNo, token)).as("用户报障的原话就是会话第一句").contains(issue);

        // 客服在运营端回一句
        post("/api/ops/cs/sessions/" + sessionNo + "/messages", Map.of("content", "已为您登记，稍后回电"),
                login("ADMIN")).okData();
        assertThat(texts(sessionNo, token))
                .as("客服的回复用户要看得到 —— 这正是此前断掉的那一环").contains("已为您登记，稍后回电");

        post("/mp/user/cs-sessions/" + sessionNo + "/messages", Map.of("content", "好的，我在 3 号柜旁边"), token).okData();
        assertThat(texts(sessionNo, token)).as("用户还得能接着说").contains("好的，我在 3 号柜旁边");
        assertThat(jdbc.queryForObject("SELECT sender_type FROM cs_message WHERE session_no=? ORDER BY id DESC LIMIT 1",
                String.class, sessionNo)).as("用户发的落 USER，不是 AGENT").isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT last_message FROM cs_session WHERE session_no=?", String.class, sessionNo))
                .as("会话摘要要跟着走，否则客服列表显示的是上一条").contains("3 号柜");
    }

    @Test
    @DisplayName("别人的会话：查不到，也发不了（与不存在同一结果）")
    void othersSessionIsInvisible() {
        String mine = consumerToken();
        post("/mp/user/report", Map.of("issue", "边界用例", "channel", "APP"), mine).okData();
        String sessionNo = get("/mp/user/cs-session", mine).okData().path("sessionNo").asText();

        String other = consumerToken();
        assertThat(get("/mp/user/cs-sessions/" + sessionNo + "/messages", other).code())
                .as("别人的会话不该读得到").isNotZero();
        assertThat(post("/mp/user/cs-sessions/" + sessionNo + "/messages", Map.of("content", "冒名发言"), other).code())
                .as("更不该替别人发言").isNotZero();
        assertThat(get("/mp/user/cs-session", other).okData().isNull())
                .as("他自己还没有会话 —— 返回空而不是报错，端上据此不显示入口").isTrue();
    }

    // —— 夹具 ——

    private String texts(String sessionNo, String token) {
        return get("/mp/user/cs-sessions/" + sessionNo + "/messages", token).okData().toString();
    }

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }
}
