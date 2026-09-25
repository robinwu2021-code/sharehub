package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误文案要跟着 {@code Accept-Language} 走。
 *
 * <h2>为什么要有这条</h2>
 * 后端的 i18n 链路早就齐了（{@code AcceptHeaderLocaleResolver} + {@code Messages} + 三份消息包），
 * ops-web 也一直在发这个头 —— 而 <b>c-app 从来没发过</b>。
 * 于是 C 端界面切到英文/阿语之后，<b>页面是英文、错误提示还是中文</b>，
 * 而错误提示恰恰是用户最需要看懂的那一句。不报错，只是读不懂。
 *
 * <p>这条用例钉的是**服务端这一半**：同一个请求，换个头就该换个语言。
 * 端上那一半（c-app 的 http-client 发不发这个头）由 TS 侧保证，
 * 但只要这条绿着，就能确定"发了有用"—— 否则端上改完也白改。
 */
class ErrorMessageLocaleTest extends ApiTestSupport {

    private final HttpClient raw = HttpClient.newHttpClient();

    /** 未登录访问受保护端点 → 403/401，走 {@code Messages} 出本地化文案。 */
    private String messageWith(String acceptLanguage) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/user/logoffs"))
                    .header("Accept-Language", acceptLanguage)
                    .GET().build();
            HttpResponse<String> r = raw.send(req, HttpResponse.BodyHandlers.ofString());
            return json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body())
                    .path("message").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("请求失败", e);
        }
    }

    @Test
    @DisplayName("★★ 同一个错误，Accept-Language 不同则文案不同——否则端上发了也白发")
    void error_message_follows_accept_language() {
        String zh = messageWith("zh-CN");
        String en = messageWith("en-AE");
        String ar = messageWith("ar-AE");

        assertThat(zh).as("前提：这个请求确实返回了一条错误文案").isNotBlank();
        assertThat(en).as("英文文案应当与中文不同 —— 相同说明 Locale 根本没生效").isNotEqualTo(zh);
        assertThat(ar).as("阿语文案应当与中文不同").isNotEqualTo(zh);

        // 光"不相等"还不够：可能三份包都填了同一串拉丁字母。
        // 阿语这条按字符集判，最直接。
        assertThat(ar.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06FF))
                .as("阿语文案里应当有阿拉伯字母，否则只是另一串英文").isTrue();
    }

    @Test
    @DisplayName("不带 Accept-Language 时回落默认语，而不是报错或出 key")
    void missing_header_falls_back_instead_of_leaking_the_key() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/user/logoffs"))
                    .GET().build();
            HttpResponse<String> r = raw.send(req, HttpResponse.BodyHandlers.ofString());
            String msg = json.readTree(r.body()).path("message").asText("");
            assertThat(msg).isNotBlank();
            assertThat(msg).as("回落时不该把 i18n key 本身吐给调用方").doesNotStartWith("error.");
        } catch (Exception e) {
            throw new IllegalStateException("请求失败", e);
        }
    }

    @Test
    @DisplayName("★ 认证端点的入参错提示也跟语言走")
    void auth_validation_message_is_localized() {
        // 刻意不用「密码错」那条：测试环境没配管理口令，登录走的是 dev-mode 免密分支，
        // 那条 401 根本到不了 —— 用一条在任何配置下都可达的（OTP 不填手机号）。
        String zh = otpMessage("zh-CN");
        String en = otpMessage("en-AE");
        String ar = otpMessage("ar-AE");

        assertThat(zh).as("前提：不填手机号确实返回了文案").isNotBlank();
        assertThat(en).as("此前这条写死中文 —— 英文界面上照样是中文").isNotEqualTo(zh);
        assertThat(en.toLowerCase()).contains("phone");
        assertThat(ar.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06FF))
                .as("阿语文案里应当有阿拉伯字母").isTrue();
    }

    /** 不填手机号请求登录验证码，取回那条 400 文案。 */
    private String otpMessage(String acceptLanguage) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/otp"))
                    .header("Content-Type", "application/json")
                    .header("Accept-Language", acceptLanguage)
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            HttpResponse<String> r = raw.send(req, HttpResponse.BodyHandlers.ofString());
            return json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body())
                    .path("message").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("请求失败", e);
        }
    }

    @Test
    @DisplayName("★★ 「找不到指定记录」也跟语言走，并带上调用方自己传的那个键")
    void not_found_message_is_localized_and_keeps_the_key() {
        String admin = login("ADMIN");
        String bogus = "PB-NOPE-" + System.nanoTime();

        // 87 处「xxx不存在: 键」收口成了一条参数化 key —— 这里验它真的带上了参数、
        // 而不是把 {0} 原样留在界面上（不传 args 时就会那样）。
        String zh = notFoundMessage(admin, bogus, "zh-CN");
        String en = notFoundMessage(admin, bogus, "en-AE");

        assertThat(zh).as("中文文案").contains(bogus);
        assertThat(zh).as("占位符必须被替换掉").doesNotContain("{0}");
        assertThat(en).as("英文文案应当与中文不同").isNotEqualTo(zh);
        assertThat(en).as("英文文案同样要带上那个键").contains(bogus);
    }

    /** 拿一个不存在的充电宝号去归档，取回那条「找不到」文案。 */
    private String notFoundMessage(String token, String bizNo, String acceptLanguage) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/ops/powerbanks/" + bizNo + "/archive"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .header("Accept-Language", acceptLanguage)
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            HttpResponse<String> r = raw.send(req, HttpResponse.BodyHandlers.ofString());
            return json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body())
                    .path("message").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("请求失败", e);
        }
    }
}
