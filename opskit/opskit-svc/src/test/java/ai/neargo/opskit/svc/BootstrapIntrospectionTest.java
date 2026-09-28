package ai.neargo.opskit.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.platform.api.account.WorkspaceView;
import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.svc.bootstrap.BootstrapDefinitions;
import ai.neargo.opskit.svc.bootstrap.OpsKitBootstrap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
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
 * 定义段：deploy/opskit/definitions.example 的流程 / SLA / 工单类型按代号幂等发布，文件名与代号不符或定义不合法都拒绝。
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
        "opskit.bootstrap.roles.ADMIN.permissions=approval:view:all,report:view,ticket:view:all,directory:manage",
        "opskit.bootstrap.roles.OPS.permissions=approval:decide,ticket:view:scoped",
        // 部署示例目录本身就是被测对象：示例改坏了这里红
        "opskit.bootstrap.definitions-dir=../../deploy/opskit/definitions.example",
        // M5-0：部署方声明的宿主应用与订阅（凭证 / 密钥为测试值）
        "opskit.bootstrap.apps.host.credential=okk_AAAAAAAAAAAAAAAAAAAAbbbbbbbbbbbbbbbbbbbbbbb",
        "opskit.bootstrap.apps.host.signing-secret=oks_SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS",
        "opskit.bootstrap.webhooks[0].app=host",
        "opskit.bootstrap.webhooks[0].url=http://127.0.0.1:18094/webhook",
        "opskit.bootstrap.webhooks[0].events=instance.*,approval.completed",
        "opskit.outbox.relay-interval=500ms"
})
class BootstrapIntrospectionTest {

    static final String APP = "okk_AAAAAAAAAAAAAAAAAAAAbbbbbbbbbbbbbbbbbbbbbbb";
    static final String SECRET = "oks_SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS";
    static final java.util.List<String[]> received = new java.util.concurrent.CopyOnWriteArrayList<>();
    static HttpServer host;
    static final AtomicInteger calls = new AtomicInteger();
    static final String WS = "http://localhost:18093/api/opskit/v1/workspaces/ext:MAIN";

    @Autowired
    OpsKitBootstrap bootstrap;

    @Autowired
    BootstrapDefinitions definitions;

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
                body = "{\"active\":true,\"sub\":\"U2\",\"name\":\"运营\",\"tenant\":\"MAIN\",\"roles\":[\"OPS\"],\"scopes\":[\"agent:AG002\"],"
                        + "\"perms\":[\"finance:withdrawal:audit\"]}";
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
        host.createContext("/webhook", ex -> {
            received.add(new String[] {ex.getRequestHeaders().getFirst(ai.neargo.opskit.platform.api.webhook.SignatureSpec.HEADER),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        host.start();
    }

    @AfterAll
    static void stopHost() {
        host.stop(0);
    }

    record Res(int status, JsonNode body, String replayed) {
        Res(int status, JsonNode body) {
            this(status, body, null);
        }
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
                .containsExactlyInAnyOrder("approval:view:all", "report:view", "ticket:view:all", "directory:manage");
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

    @Test
    void deployedDefinitionsArePublishedOnceAndAreUsable() throws Exception {
        bootstrap.run();
        assertThat(bootstrap.lastDefinitions().published()).as("启动时已发布，内容没变不出新版本").isEmpty();
        assertThat(bootstrap.lastDefinitions().unchanged()).containsExactly("process:repair_flow", "process:withdrawal_audit", "sla:repair_sla",
                "ticket-type:repair");

        Res types = get(WS + "/ticket-types", "stk_admin");
        assertThat(types.status()).isEqualTo(200);
        assertThat(types.body().toString()).contains("\"repair\"").contains("repair_flow");
    }

    @Test
    void misnamedOrInvalidDefinitionFilesStopTheStartup(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        WorkspaceView ws = bootstrap.run();
        TenantContext t = new TenantContext(new AccountId(ws.accountId()), new WorkspaceId(ws.workspaceId()));
        Actor system = new Actor(t, Optional.empty(), Optional.of("opskit-bootstrap"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        String flow = Files.readString(Path.of("../../deploy/opskit/definitions.example/processes/repair_flow.json"));
        Files.createDirectories(dir.resolve("processes"));

        Files.writeString(dir.resolve("processes/other_name.json"), flow);
        assertThatThrownBy(() -> definitions.publishAll(system, t.workspaceId(), dir)).hasMessageContaining("other_name.json").hasMessageContaining("repair_flow");

        Files.delete(dir.resolve("processes/other_name.json"));
        Files.writeString(dir.resolve("processes/repair_flow.json"), flow.replace("\"to\": \"done\"", "\"to\": \"nowhere\""));
        assertThatThrownBy(() -> definitions.publishAll(system, t.workspaceId(), dir)).hasMessageContaining("repair_flow.json")
                .hasMessageContaining("opskit.validation");
    }

    Res send(String method, String url, String token, String idemKey, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        HttpResponse<String> r = http.send(b.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isEmpty() ? null : json.readTree(r.body()), r.headers().firstValue("Idempotent-Replayed").orElse(null));
    }

    /**
     * M5-0 试点闭环：宿主用应用凭证代提交人送审（幂等）→ 审核人在 OpsKit 驳回 → 宿主收到签名的 approval.completed，
     * 其中带业务对象与决定人的宿主编号，信封带宿主租户号。
     */
    @Test
    void hostSubmitsOnBehalfIdempotentlyAndReceivesSignedDecision() throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("processCode", "withdrawal_audit");
        body.put("subject", java.util.Map.of("type", "withdrawal", "ref", "WD001", "display", java.util.Map.of("title", "提现 AED 1,200 · AG002")));
        body.put("fields", java.util.Map.of("amount", 1200));
        body.put("scopes", List.of("agent:AG002"));
        body.put("onBehalfOf", java.util.Map.of("externalRef", "AG-U9", "displayName", "代理九"));

        Res first = send("POST", WS + "/instances", APP, "opskit:withdrawal:WD001:1", body);
        assertThat(first.status()).isEqualTo(200);
        String instanceNo = first.body().get("instanceNo").asText();
        String approvalNo = first.body().get("approvalNo").asText();
        assertThat(approvalNo).as("停在审批门，回带审批单号").isNotBlank();

        Res replay = send("POST", WS + "/instances", APP, "opskit:withdrawal:WD001:1", body);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.replayed()).isEqualTo("true");
        assertThat(replay.body().get("instanceNo").asText()).as("重投拿到同一个实例").isEqualTo(instanceNo);

        java.util.Map<String, Object> other = new java.util.LinkedHashMap<>(body);
        other.put("fields", java.util.Map.of("amount", 999));
        Res mismatch = send("POST", WS + "/instances", APP, "opskit:withdrawal:WD001:1", other);
        assertThat(mismatch.status()).isEqualTo(409);
        assertThat(mismatch.body().get("error").asText()).isEqualTo("opskit.idempotency.mismatch");

        assertThat(send("POST", WS + "/instances", "stk_ops", null, body).status()).as("成员令牌不能代人").isEqualTo(403);

        String task = get(WS + "/approvals/" + approvalNo, "stk_ops").body().get("mine").get("taskNo").asText();
        Res decided = send("POST", WS + "/approvals/" + approvalNo + "/decide", "stk_ops", null,
                java.util.Map.of("taskNo", task, "decision", "REJECT", "reason", "账户名与实名不符"));
        assertThat(decided.status()).isEqualTo(200);

        tools.jackson.databind.JsonNode completed = awaitEvent("approval.completed", approvalNo);
        assertThat(completed.get("workspaceExternalRef").asText()).isEqualTo("MAIN");
        assertThat(completed.at("/data/subject/type").asText()).isEqualTo("withdrawal");
        assertThat(completed.at("/data/subject/ref").asText()).isEqualTo("WD001");
        assertThat(completed.at("/data/result").asText()).isEqualTo("REJECTED");
        assertThat(completed.at("/data/reason").asText()).isEqualTo("账户名与实名不符");
        assertThat(completed.at("/data/decidedBy/externalRef").asText()).as("决定人的宿主编号").isEqualTo("U2");
        assertThat(completed.at("/data/decidedBy/displayName").asText()).isEqualTo("运营");

        tools.jackson.databind.JsonNode created = awaitEvent("instance.created", instanceNo);
        assertThat(created.at("/data/actor/externalRef").asText()).as("发起人 = 代提交的代理").isEqualTo("AG-U9");
    }

    /** 等某类事件送达（投递间隔 500ms）；同时验签 —— 用错密钥必须验不过。 */
    private tools.jackson.databind.JsonNode awaitEvent(String type, String mustContain) throws Exception {
        for (int i = 0; i < 60; i++) {
            for (String[] r : received) {
                tools.jackson.databind.JsonNode env = json.readTree(r[1]);
                if (type.equals(env.get("eventType").asText()) && r[1].contains(mustContain)) {
                    long now = System.currentTimeMillis();
                    assertThat(ai.neargo.opskit.platform.api.webhook.SignatureSpec.verify(SECRET, r[0], r[1], now)).isTrue();
                    assertThat(ai.neargo.opskit.platform.api.webhook.SignatureSpec.verify("oks_wrong", r[0], r[1], now)).isFalse();
                    return env;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("没收到 " + type + "（" + mustContain + "）；已收到 " + received.size() + " 条");
    }

    /** 目录同步只认应用凭证；同步进来的成员（没登录过）即可被查到 / 指派；组与属性定义可维护。 */
    @Test
    void hostSyncsTheDirectoryWithItsApplicationCredential() throws Exception {
        java.util.Map<String, Object> batch = java.util.Map.of("mode", "MERGE",
                "members", List.of(java.util.Map.of("externalRef", "AG-100", "displayName", "代理一百", "attrs", java.util.Map.of("partyType", "AGENT"),
                        "scopes", List.of("agent:AG100"))),
                "groups", List.of(java.util.Map.of("code", "agent_co_100", "name", java.util.Map.of("zh", "一百号代理公司"), "kind", "TEAM")),
                "memberships", java.util.Map.of("agent_co_100", List.of("AG-100")));
        assertThat(send("POST", WS + "/directory/sync", "stk_admin", null, batch).status()).as("成员令牌（哪怕持 directory:manage）不能灌目录").isEqualTo(403);
        Res synced = send("POST", WS + "/directory/sync", APP, null, batch);
        assertThat(synced.status()).isEqualTo(200);
        assertThat(synced.body().get("members").asInt()).isEqualTo(1);

        Res found = send("POST", WS + "/members/query", "stk_admin", null, java.util.Map.of("keyword", "代理一百"));
        assertThat(found.body().get("total").asLong()).as("没登录过也在目录里").isEqualTo(1);

        assertThat(send("PUT", WS + "/groups/night_ops", APP, null, java.util.Map.of("name", java.util.Map.of("zh", "夜班"), "kind", "QUEUE",
                "claimTimeoutSec", 900)).status()).isEqualTo(200);
        assertThat(send("PUT", WS + "/member-attributes/partyType", APP, null, java.util.Map.of("name", java.util.Map.of("zh", "身份"),
                "valueLabels", java.util.Map.of("AGENT", java.util.Map.of("zh", "代理"), "STAFF", java.util.Map.of("zh", "员工")))).status()).isEqualTo(200);
        assertThat(get(WS + "/member-attributes", "stk_admin").body().toString()).contains("partyType");
    }
}
