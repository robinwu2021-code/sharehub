package ai.neargo.opskit.svc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.platform.api.account.WorkspaceView;
import ai.neargo.opskit.svc.bootstrap.OpsKitBootstrap;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 21/07 O4-1：启动引导（账户 · 空间 · 按前缀路由的内省 IdP · 角色 → 产品权限）幂等；成员令牌经宿主内省端点换成操作人。
 * 宿主的内省端点由本测试起一个小 HTTP 服务假扮（RFC 7662：表单 token + Basic 认证）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18093",
        "spring.datasource.url=jdbc:h2:mem:bootstrap;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "opskit.crypto.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "opskit.bootstrap.enabled=true",
        "opskit.bootstrap.workspace.ref=MAIN",
        "opskit.bootstrap.workspace.zone=Asia/Shanghai",
        "opskit.bootstrap.idps[0].issuer=host-staff",
        "opskit.bootstrap.idps[0].kind=INTROSPECT",
        "opskit.bootstrap.idps[0].introspect-url=http://127.0.0.1:18094/introspect",
        "opskit.bootstrap.idps[0].client-id=opskit",
        "opskit.bootstrap.idps[0].client-secret=s3cret",
        "opskit.bootstrap.idps[0].token-prefix=stk_",
        "opskit.bootstrap.idps[0].claims.member=sub",
        "opskit.bootstrap.idps[0].claims.displayName=name",
        "opskit.bootstrap.idps[0].claims.workspace=tenant",
        "opskit.bootstrap.idps[0].claims.roles=roles",
        "opskit.bootstrap.idps[0].claims.perms=perms",
        "opskit.bootstrap.idps[0].claims.scopes=scopes",
        "opskit.bootstrap.roles.ADMIN.permissions=approval:view:all,report:view,ticket:view:all",
        "opskit.bootstrap.roles.OPS.permissions=approval:decide,ticket:view:scoped"
})
class BootstrapIntrospectionTest {

    static HttpServer host;
    static final AtomicInteger calls = new AtomicInteger();
    static final String WS = "http://localhost:18093/api/opskit/v1/workspaces/ext:MAIN";

    @Autowired
    OpsKitBootstrap bootstrap;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    /** 假宿主：Basic 不对 → 401；stk_admin / stk_ops 有效；其余 active:false。 */
    @BeforeAll
    static void startHost() throws IOException {
        host = HttpServer.create(new InetSocketAddress("127.0.0.1", 18094), 0);
        host.createContext("/introspect", ex -> {
            calls.incrementAndGet();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String expected = "Basic " + java.util.Base64.getEncoder().encodeToString("opskit:s3cret".getBytes(StandardCharsets.UTF_8));
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body;
            int status = 200;
            if (!expected.equals(auth)) {
                status = 401;
                body = "{}";
            } else if (form.contains("token=stk_admin")) {
                body = "{\"active\":true,\"sub\":\"U1\",\"name\":\"管理员\",\"tenant\":\"MAIN\",\"roles\":[\"ADMIN\"],\"perms\":[\"flow:todo:read\"],\"scopes\":[\"*\"]}";
            } else if (form.contains("token=stk_ops")) {
                body = "{\"active\":true,\"sub\":\"U2\",\"name\":\"运营\",\"tenant\":\"MAIN\",\"roles\":[\"OPS\"],\"scopes\":[\"agent:AG002\"]}";
            } else if (form.contains("token=stk_other")) {
                body = "{\"active\":true,\"sub\":\"U3\",\"tenant\":\"OTHER\"}";
            } else {
                body = "{\"active\":false}";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        host.start();
    }

    @AfterAll
    static void stopHost() {
        host.stop(0);
    }

    record Res(int status, JsonNode body) {
    }

    Res get(String url, String token) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isEmpty() ? null : json.readTree(r.body()));
    }

    @Test
    void anOpaqueStaffTokenBecomesAMemberWithRoleMappedPermissions() throws Exception {
        Res me = get(WS + "/me", "stk_admin");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("roles").valueStream().map(JsonNode::asText).toList()).containsExactly("ADMIN");
        assertThat(me.body().get("permissions").valueStream().map(JsonNode::asText).toList())
                .containsExactlyInAnyOrder("approval:view:all", "report:view", "ticket:view:all");
        assertThat(me.body().get("externalPerms").valueStream().map(JsonNode::asText).toList()).containsExactly("flow:todo:read");
        assertThat(me.body().get("scopes").valueStream().map(JsonNode::asText).toList()).containsExactly("*");

        Res ops = get(WS + "/me", "stk_ops");
        assertThat(ops.body().get("permissions").valueStream().map(JsonNode::asText).toList()).containsExactlyInAnyOrder("approval:decide", "ticket:view:scoped");
        assertThat(ops.body().get("scopes").valueStream().map(JsonNode::asText).toList()).containsExactly("agent:AG002");
    }

    @Test
    void invalidTokensAndOtherTenantsAreRejected() throws Exception {
        assertThat(get(WS + "/me", "stk_forged").status()).as("宿主说 active:false").isEqualTo(401);
        assertThat(get(WS + "/me", "stk_other").status()).as("令牌属于别的租户：按不存在").isEqualTo(404);
        int before = calls.get();
        get(WS + "/me", "stk_admin");
        get(WS + "/me", "stk_admin");
        assertThat(calls.get() - before).as("有效令牌的内省结果缓存（上限 5 分钟），不是每个请求都打宿主").isLessThanOrEqualTo(1);
    }

    @Test
    void bootstrapIsIdempotent() {
        WorkspaceView first = bootstrap.run();
        WorkspaceView again = bootstrap.run();
        assertThat(again.workspaceId()).isEqualTo(first.workspaceId());
        assertThat(again.externalRef()).isEqualTo("MAIN");
        assertThat(List.of(first.accountId())).containsExactly(again.accountId());
    }
}
