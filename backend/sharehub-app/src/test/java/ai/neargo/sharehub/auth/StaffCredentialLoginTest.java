package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.platform.cred.service.CredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 员工凭据登录（P3b · B2）。
 *
 * <h2>为什么不继承 ApiTestSupport</h2>
 * 那套集成测试开着 dev-mode（免密、角色由请求带），于是**看不见凭据这条路** ——
 * dev-mode 分支在凭据分支之后，一旦回落写错，dev-mode 会把它盖住，测试照样绿。
 * 本类刻意摆出**生产姿态**：dev-mode 关、配一个共享口令，
 * 与 {@code powerbank.ichain.top} 上的配置一致。
 *
 * <h2>最重要的一条是回落</h2>
 * 凭据表里没有这个人时，系统行为必须与接这段代码之前**完全一致**。
 * 它错了的症状是「上线那一刻所有人都进不来」，而这种故障只能靠改库或改配置自救 ——
 * 所以它排在第一个用例。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sharehub.dev-mode.enabled=false",
                "sharehub.admin.password=" + StaffCredentialLoginTest.SHARED_PASSWORD,
        })
class StaffCredentialLoginTest {

    static final String SHARED_PASSWORD = "ItsATestSharedPwd!2026";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CredentialService credentials;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    /** 每个用例自己造员工号，互不干扰；共享测试库里留不下痕迹。 */
    private final String emp = "ECR" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM cred_credential WHERE subject_no=?", emp);
        jdbc.update("DELETE FROM iam_employee_role WHERE employee_no=?", emp);
        jdbc.update("DELETE FROM iam_employee WHERE employee_no=?", emp);
    }

    // ───────────────────── 回落 ─────────────────────

    @Test
    @DisplayName("★ 凭据表里没有这个人时，admin + 共享口令照常登录（回落没断——这条最重要）")
    void shared_password_gate_still_works_when_there_is_no_credential() {
        jdbc.update("DELETE FROM cred_credential WHERE realm='STAFF' AND subject_no='admin'");

        JsonNode ok = login("admin", SHARED_PASSWORD);
        assertThat(ok.path("data").path("token").asText("")).as("回落断了：%s", ok).isNotEmpty();
        assertThat(ok.path("data").path("mustChange").asBoolean()).as("共享口令这条路没有强制改密").isFalse();

        // 口令错仍然拒 —— 否则上面那条通过只是因为闸门整个是开的
        assertThat(login("admin", "wrong-" + SHARED_PASSWORD).path("data").path("token").asText(""))
                .as("负对照：口令错也发了 token，说明闸门根本没生效").isEmpty();
    }

    // ───────────────────── 凭据登录 ─────────────────────

    @Test
    @DisplayName("建号后能用一次性口令登录，首次带 mustChange，角色来自员工档案而不是请求")
    void credential_login_uses_the_role_from_the_employee_directory() {
        employee("ACTIVE");
        role("OPS");
        String temp = credentials.resetPassword("STAFF", emp);

        JsonNode d = login(emp, temp).path("data");
        assertThat(d.path("token").asText("")).isNotEmpty();
        assertThat(d.path("mustChange").asBoolean()).as("一次性口令必须要求改密").isTrue();
        assertThat(d.path("role").asText()).as("角色只能来自档案").isEqualTo("OPS");

        // 请求里带别的角色也不管用（登录页曾经可以自选角色）
        assertThat(loginWithRole(emp, temp, "ADMIN").path("data").path("role").asText())
                .as("请求里的 role 不该被受理").isEqualTo("OPS");
    }

    @Test
    @DisplayName("★ 档案里没配角色的员工登进来是 VIEWER，不是 ADMIN")
    void credential_login_without_a_role_is_not_an_admin() {
        /*
         * 这一条守的是一个很容易写出来的洞：凭据命中后把 role 兜底成 adminRole（ADMIN），
         * 想着「反正下面会被档案覆盖」—— 而档案里没配角色时**不会覆盖**，
         * 且下方「perms 为空则降级 VIEWER」那段兜底对 ADMIN 显式豁免，于是这个人就是超管。
         */
        employee("ACTIVE");   // 故意不配角色
        String temp = credentials.resetPassword("STAFF", emp);

        JsonNode d = login(emp, temp).path("data");
        assertThat(d.path("token").asText("")).isNotEmpty();
        assertThat(d.path("role").asText()).as("没配角色不该变成超管").isEqualTo("VIEWER");
        // VIEWER 在库里是有一批只读权限码的，所以不能断言「一个都没有」——
        // 要断言的是**没有写权限**：这才是「没配角色不等于超管」的实质。
        for (JsonNode p : d.path("perms")) {
            assertThat(p.asText()).as("没配角色的人拿到了写权限：%s", p.asText())
                    .doesNotContain(":create").doesNotContain(":update").doesNotContain(":delete")
                    .doesNotContain(":approve").doesNotContain(":audit");
        }
    }

    @Test
    @DisplayName("★ 离职员工的凭据没停用时登录也要拒（不然离职即失权是句空话）")
    void a_left_employee_cannot_log_in_even_with_a_valid_credential() {
        employee("ACTIVE");
        role("OPS");
        String temp = credentials.resetPassword("STAFF", emp);
        assertThat(login(emp, temp).path("data").path("token").asText("")).as("先确认这个口令本来是能登的").isNotEmpty();

        jdbc.update("UPDATE iam_employee SET status='LEFT' WHERE employee_no=?", emp);

        assertThat(login(emp, temp).path("data").path("token").asText(""))
                .as("离职后同一个口令仍然登进来了").isEmpty();
    }

    @Test
    @DisplayName("改密后旧口令立刻失效，mustChange 也随之落下")
    void changing_the_password_invalidates_the_old_one() {
        employee("ACTIVE");
        role("OPS");
        String temp = credentials.resetPassword("STAFF", emp);
        String token = login(emp, temp).path("data").path("token").asText();

        JsonNode r = post("/api/auth/password", Map.of("oldPassword", temp, "newPassword", "NewPwd!2026x"), token);
        assertThat(r.path("code").asInt(-1)).as("改密失败：%s", r).isZero();

        assertThat(login(emp, temp).path("data").path("token").asText("")).as("旧口令还能用").isEmpty();
        JsonNode d = login(emp, "NewPwd!2026x").path("data");
        assertThat(d.path("token").asText("")).isNotEmpty();
        assertThat(d.path("mustChange").asBoolean()).as("改过密就不该再被要求改密").isFalse();
    }

    // ───────────────────── 锁定 ─────────────────────

    @Test
    @DisplayName("★ 连错 5 次锁定，且锁定期内口令正确也拒（放行的话锁形同虚设）")
    void the_account_locks_after_five_failures_and_stays_locked_for_the_right_password() {
        employee("ACTIVE");
        role("OPS");
        String temp = credentials.resetPassword("STAFF", emp);

        for (int i = 1; i <= 5; i++) {
            assertThat(login(emp, "nope-" + i).path("data").path("token").asText("")).as("第 %s 次错口令", i).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT locked_until FROM cred_credential WHERE subject_no=?", Object.class, emp))
                .as("连错 5 次之后应当已上锁").isNotNull();

        assertThat(login(emp, temp).path("data").path("token").asText(""))
                .as("锁定期内口令正确也必须拒 —— 否则爆破到正确口令那一刻锁就白上了").isEmpty();

        // 管理员重置即解锁（否则被恶意锁住的人只能等）
        String fresh = credentials.resetPassword("STAFF", emp);
        assertThat(login(emp, fresh).path("data").path("token").asText("")).as("重置口令应当同时解锁").isNotEmpty();
    }

    // ───────────────────── 建号端点 ─────────────────────

    @Test
    @DisplayName("建号端点回一次性口令；口令不出现在任何日志里")
    void the_reset_endpoint_returns_a_one_time_password_that_never_reaches_the_log() {
        employee("ACTIVE");
        role("OPS");
        String admin = login("admin", SHARED_PASSWORD).path("data").path("token").asText();

        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender.start();
        root.addAppender(appender);
        String pwd;
        try {
            JsonNode d = post("/api/platform/employees/" + emp + "/credential", Map.of(), admin).path("data");
            pwd = d.path("password").asText("");
            assertThat(pwd).as("建号应当回一次性口令").hasSizeGreaterThanOrEqualTo(8);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        for (var e : appender.list) {
            assertThat(e.getFormattedMessage()).as("一次性口令进了日志：%s", e.getFormattedMessage()).doesNotContain(pwd);
        }
        assertThat(login(emp, pwd).path("data").path("token").asText("")).as("回的口令应当真的能登").isNotEmpty();
    }

    @Test
    @DisplayName("离职的人不给建号——既然离职要停用凭据，就不该有一条路把它激活回来")
    void a_left_employee_cannot_be_given_a_credential() {
        employee("LEFT");
        String admin = login("admin", SHARED_PASSWORD).path("data").path("token").asText();
        Resp r = postRaw("/api/platform/employees/" + emp + "/credential", "{}", admin);
        assertThat(r.status).as("%s", r.body).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cred_credential WHERE subject_no=?", Integer.class, emp))
                .isZero();
    }

    // ───────────────────── 夹具 ─────────────────────

    private void employee(String status) {
        jdbc.update("INSERT INTO iam_employee (employee_no, tenant_id, name, status) VALUES (?, 'MAIN', ?, ?)",
                emp, "【测试】凭据登录", status);
    }

    private void role(String roleCode) {
        jdbc.update("INSERT INTO iam_employee_role (employee_no, role_no) VALUES (?, ?)", emp, roleCode);
    }

    private JsonNode login(String username, String password) {
        return postRaw("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null).body;
    }

    private JsonNode loginWithRole(String username, String password, String role) {
        return postRaw("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}", null).body;
    }

    private JsonNode post(String path, Map<String, ?> body, String token) {
        try {
            return postRaw(path, json.writeValueAsString(body), token).body;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Resp(int status, JsonNode body) {
    }

    private Resp postRaw(String path, String rawBody, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(rawBody));
            if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(res.statusCode(), json.readTree(res.body().isBlank() ? "{}" : res.body()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
