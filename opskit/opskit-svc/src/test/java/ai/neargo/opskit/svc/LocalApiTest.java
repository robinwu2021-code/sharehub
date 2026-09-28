package ai.neargo.opskit.svc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.svc.dev.DevSeed;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 21/07 O4-0：local 配置档整进程联调冒烟 —— 真 HTTP、真令牌（测试 IdP 签、服务自己的 JWKS 验）、真种子。
 * 钉住前端联调场景的服务端一半：待办负对照 · 决定的 409 → 403 → 422 · 并发领取 · 统一错误体。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {"server.port=18090",
        "opskit.dev.key-file=${java.io.tmpdir}/opskit-test-idp-key.json"})
@ActiveProfiles("local")
class LocalApiTest {

    private static final String BASE = "http://localhost:18090";
    private static final String WS = BASE + "/api/opskit/v1/workspaces/ext:" + DevSeed.TENANT;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    DevSeed seed;

    record Res(int status, JsonNode body) {
    }

    private Res call(String method, String url, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header("Accept-Language", "zh");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isEmpty() ? null : json.readTree(r.body()));
    }

    private String token(String persona) throws Exception {
        return call("POST", BASE + "/dev/token", null, Map.of("as", persona)).body().get("token").asText();
    }

    private String fixture(String name, String field) {
        @SuppressWarnings("unchecked")
        Map<String, String> m = (Map<String, String>) seed.manifest().fixtures().get(name);
        return m.get(field);
    }

    @Test
    void todosExcludeTheInstanceTheMemberCannotSeeAndCountsMatch() throws Exception {
        String alice = token("alice");
        Res count = call("POST", WS + "/todos/count", alice, Map.of());
        Res list = call("POST", WS + "/todos/query", alice, Map.of("origin", "ALL", "page", 1, "size", 50));
        assertThat(count.status()).isEqualTo(200);
        assertThat(list.body().get("total").asLong()).isEqualTo(count.body().get("all").asLong());
        List<String> refs = list.body().get("list").valueStream().map(n -> n.get("subject").get("ref").asText()).toList();
        // 用例共用一套种子、执行顺序不定：只断言不会被别的用例改动的单（C-1002 自审单无人能办；C-1003 是负对照）
        assertThat(refs).contains("C-1002").doesNotContain("C-1003");
    }

    @Test
    void decideChecksConflictThenPermissionThenValidation() throws Exception {
        String alice = token("alice");
        String viewer = token("viewer");
        String no = fixture("approvalTwoStep", "approvalNo");
        String task = call("GET", WS + "/approvals/" + no, alice, null).body().get("mine").get("taskNo").asText();

        Res noReason = call("POST", WS + "/approvals/" + no + "/decide", alice, Map.of("taskNo", task, "decision", "REJECT"));
        assertThat(noReason.status()).isEqualTo(422);
        assertThat(noReason.body().get("error").asText()).isEqualTo("opskit.approval.reason_required");
        assertThat(noReason.body().get("traceId").asText()).hasSize(32);

        assertThat(call("POST", WS + "/approvals/" + no + "/decide", viewer, Map.of("taskNo", task, "decision", "APPROVE")).status()).isEqualTo(403);

        Res ok = call("POST", WS + "/approvals/" + no + "/decide", alice, Map.of("taskNo", task, "decision", "APPROVE"));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("currentStep").get("code").asText()).as("条件成立，进入会签步").isEqualTo("finance");

        Res again = call("POST", WS + "/approvals/" + no + "/decide", alice, Map.of("taskNo", task, "decision", "REJECT"));
        assertThat(again.status()).as("任务已处理：先 409，哪怕这次又没写原因").isEqualTo(409);
        assertThat(again.body().get("error").asText()).isEqualTo("opskit.state_changed");
    }

    @Test
    void selfSubmittedApprovalExplainsWhyNot() throws Exception {
        Res v = call("GET", WS + "/approvals/" + fixture("approvalMine", "approvalNo"), token("alice"), null);
        assertThat(v.body().get("mine").get("canDecide").asBoolean()).isFalse();
        assertThat(v.body().get("mine").get("whyNot").asText()).isEqualTo("opskit.approval.self_review");
    }

    @Test
    void claimingATaskSomeoneElseHoldsIsAConflict() throws Exception {
        String claimed = (String) seed.manifest().fixtures().get("claimedByFrank");
        Res r = call("POST", WS + "/tasks/" + claimed + "/claim", token("alice"), null);
        assertThat(r.status()).isEqualTo(409);
        Res mine = call("POST", WS + "/tasks/query", token("frank"), Map.of("assignee", "me", "claimed", true, "page", 1, "size", 20));
        assertThat(mine.body().get("list").valueStream().map(n -> n.get("taskNo").asText()).toList()).contains(claimed);
    }

    @Test
    void errorsShareOneBody() throws Exception {
        Res noToken = call("POST", WS + "/todos/count", null, Map.of());
        assertThat(noToken.status()).isEqualTo(401);
        assertThat(noToken.body().get("error").asText()).isEqualTo("opskit.unauthenticated");
        Res tooBig = call("POST", WS + "/todos/query", token("alice"), Map.of("page", 1, "size", 500));
        assertThat(tooBig.status()).isEqualTo(422);
        assertThat(tooBig.body().get("errors").get(0).get("path").asText()).isEqualTo("size");
        assertThat(call("POST", BASE + "/api/opskit/v1/workspaces/ext:NOPE/todos/count", token("alice"), Map.of()).status())
                .as("令牌属于 T001，访问别的租户按不存在").isEqualTo(404);
    }

    @Test
    void approvalTemplatesPublishIdempotentlyAndReportFieldPaths() throws Exception {
        String admin = token("admin");
        Map<String, Object> tpl = Map.of("code", "leave_tpl", "name", Map.of("zh", "请假审批", "en", ""),
                "steps", List.of(Map.of("code", "lead", "name", Map.of("zh", "主管"), "approver", Map.of("kind", "MEMBER", "value", List.of("mb_x")), "mode", "ANY")),
                "rules", Map.of("selfApproval", false, "crossStepExclusive", true));
        Res first = call("PUT", WS + "/approval-templates/leave_tpl", admin, tpl);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("created").asBoolean()).isTrue();
        Res again = call("PUT", WS + "/approval-templates/leave_tpl", admin, tpl);
        assertThat(again.body().get("created").asBoolean()).as("内容没变不出新版本").isFalse();
        assertThat(again.body().get("version").asText()).isEqualTo(first.body().get("version").asText());

        Res got = call("GET", WS + "/approval-templates/leave_tpl", admin, null);
        assertThat(got.body().get("steps").get(0).get("approver").get("value").isArray()).as("MEMBER 的 value 是列表").isTrue();
        assertThat(got.body().get("name").has("en")).as("空白语种不存").isFalse();

        Map<String, Object> bad = Map.of("name", Map.of("zh", "坏的"),
                "steps", List.of(Map.of("code", "s1", "name", Map.of("zh", "会签"), "approver", Map.of("kind", "ROLE", "value", "approver"), "mode", "ALL")));
        Res rejected = call("PUT", WS + "/approval-templates/bad_tpl", admin, bad);
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.body().get("errors").get(0).get("path").asText()).isEqualTo("steps[0].mode");

        assertThat(call("PUT", WS + "/approval-templates/leave_tpl", token("alice"), tpl).status()).as("无 process:manage").isEqualTo(403);
        List<String> codes = call("GET", WS + "/approval-templates", token("alice"), null).body().valueStream().map(n -> n.get("code").asText()).toList();
        assertThat(codes).contains("contract_approval", "evidence_review", "leave_tpl");
    }

    @Test
    void slaPoliciesRoundTripAndTodosCarryTheNearestClock() throws Exception {
        String admin = token("admin");
        Map<String, Object> policy = Map.of("name", Map.of("zh", "响应时限"),
                "metrics", List.of(Map.of("code", "FIRST_RESPONSE", "name", Map.of("zh", "首次响应"))),
                "targets", List.of(Map.of("when", Map.of("field", "priority", "op", "EQ", "value", "P1"), "targetsMin", Map.of("FIRST_RESPONSE", 30)),
                        Map.of("targetsMin", Map.of("FIRST_RESPONSE", 120))),
                "warnAtPercent", 80);
        Res put = call("PUT", WS + "/sla-policies/resp_sla", admin, policy);
        assertThat(put.status()).isEqualTo(200);
        assertThat(call("PUT", WS + "/sla-policies/resp_sla", admin, policy).body().get("created").asBoolean()).isFalse();
        Res got = call("GET", WS + "/sla-policies/resp_sla", admin, null);
        assertThat(got.body().get("targets").get(0).get("targetsMin").get("FIRST_RESPONSE").asLong()).isEqualTo(30);
        assertThat(got.body().get("targets").get(0).get("when").get("op").asText()).isEqualTo("EQ");

        Res bad = call("PUT", WS + "/sla-policies/bad_sla", admin, Map.of("metrics", List.of(Map.of("code", "X")),
                "targets", List.of(Map.of("targetsMin", Map.of("Y", 10)))));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().get("errors").valueStream().map(n -> n.get("path").asText()).toList()).contains("targets[0].targets.Y");
        assertThat(call("PUT", WS + "/sla-policies/resp_sla", token("alice"), policy).status()).as("无 sla:manage").isEqualTo(403);

        Res todos = call("POST", WS + "/todos/query", token("alice"), Map.of("origin", "ALL", "page", 1, "size", 50));
        JsonNode evidence = todos.body().get("list").valueStream().filter(n -> n.get("subject").get("ref").asText().startsWith("E-")).findFirst().orElseThrow();
        assertThat(evidence.get("sla").get("state").asText()).isEqualTo("RUNNING");
        assertThat(evidence.get("sla").get("dueAtMs").asLong() - evidence.get("sla").get("startedAtMs").asLong()).isEqualTo(4 * 3600_000L);
    }

    @Test
    void historyReconcileAndStats() throws Exception {
        String bob = token("bob");
        String no = fixture("approvalMine", "instanceNo");
        String twoStep = fixture("approvalTwoStep", "instanceNo");
        Res h = call("GET", WS + "/instances/" + twoStep + "/history", bob, null);
        assertThat(h.status()).isEqualTo(200);
        assertThat(h.body().valueStream().map(n -> n.get("action").asText()).toList()).contains("created", "submit");
        assertThat(h.body().get(0).get("actor").get("displayName").asText()).isEqualTo("Bob 业务员");
        assertThat(call("GET", WS + "/instances/" + no + "/history", bob, null).status()).as("alice 自己提交的单，bob 无关").isEqualTo(404);

        Res s = call("GET", WS + "/subjects/contract/C-1001/history", token("alice"), null);
        assertThat(s.body().get("total").asLong()).isEqualTo(1);
        assertThat(s.body().get("instances").get(0).get("approvals").size()).isEqualTo(1);

        Res q = call("POST", WS + "/instances/query", bob, Map.of("processCode", "contract_approval", "page", 1, "size", 20));
        assertThat(q.body().get("total").asLong()).as("bob 提交的两张合同").isEqualTo(2);

        assertThat(call("POST", WS + "/process-stats", bob, Map.of()).status()).as("无 report:view").isEqualTo(403);
        assertThat(call("POST", WS + "/process-stats", token("admin"), Map.of()).status()).isEqualTo(200);
        Res one = call("GET", WS + "/instances/" + twoStep, bob, null);
        assertThat(one.status()).isEqualTo(200);
        assertThat(one.body().get("process").get("code").asText()).isEqualTo("contract_approval");
        assertThat(call("GET", WS + "/instances/" + no, bob, null).status()).isEqualTo(404);
        assertThat(h.body().valueStream().filter(n -> "submit".equals(n.get("action").asText())).findFirst().orElseThrow().get("actionName").asText())
                .isEqualTo("提交");
    }

    @Test
    void ticketsFirstBatch() throws Exception {
        String alice = token("alice");
        Res types = call("GET", WS + "/ticket-types", alice, null);
        assertThat(types.body().valueStream().map(n -> n.get("code").asText()).toList()).contains("repair");
        JsonNode repair = types.body().valueStream().filter(n -> "repair".equals(n.get("code").asText())).findFirst().orElseThrow();
        assertThat(repair.get("priorityOptions").size()).isEqualTo(3);

        Res created = call("POST", WS + "/tickets", token("bob"), Map.of("typeCode", "repair", "title", "扫码无反应", "priority", "P1",
                "fields", Map.of("site", "S1", "source", "APP", "sourceRef", "SR-9"), "subject", Map.of("type", "cabinet", "ref", "CAB-9")));
        assertThat(created.status()).isEqualTo(200);
        String no = created.body().get("ticketNo").asText();
        assertThat(created.body().get("attached").asBoolean()).isFalse();
        assertThat(created.body().get("priority").get("name").asText()).isEqualTo("紧急");
        assertThat(created.body().get("fields").get("site").get("name").asText()).isEqualTo("一号店");
        assertThat(created.body().get("subject").get("ref").asText()).isEqualTo("CAB-9");
        assertThat(created.body().get("slaMetrics").size()).isEqualTo(2);

        Res claimed = call("POST", WS + "/tickets/" + no + "/claim", alice, null);
        assertThat(claimed.status()).isEqualTo(200);
        assertThat(claimed.body().get("assignee").get("displayName").asText()).isEqualTo("Alice 审批人");
        assertThat(claimed.body().get("actions").valueStream().map(a -> a.get("code").asText()).toList()).contains("accept");

        Res accepted = call("POST", WS + "/tickets/" + no + "/transitions/accept", alice, Map.of());
        assertThat(accepted.body().get("status").get("name").asText()).isEqualTo("处理中");
        Res noComment = call("POST", WS + "/tickets/" + no + "/transitions/resolve", alice, Map.of("resolution", "FIXED"));
        assertThat(noComment.status()).as("解决须写说明").isEqualTo(422);
        Res resolved = call("POST", WS + "/tickets/" + no + "/transitions/resolve", alice, Map.of("comment", "换了扫码头", "resolution", "FIXED"));
        assertThat(resolved.body().get("open").asBoolean()).isFalse();
        assertThat(resolved.body().get("resolution").get("name").asText()).isEqualTo("已修复");

        Res list = call("POST", WS + "/tickets/query", alice, Map.of("typeCode", List.of("repair"), "statusCategory", List.of("NEW"), "sort", "priority",
                "page", 1, "size", 20));
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().get("total").asLong()).isEqualTo(list.body().get("list").size());
        JsonNode first = list.body().get("list").get(0);
        assertThat(first.get("priority").get("code").asText()).as("按优先级排，P1 在前").isEqualTo("P1");
        assertThat(first.get("fields").get("source").get("name").asText()).isNotBlank();
        assertThat(first.has("fieldList")).as("列表行不带详情字段").isFalse();
        Res byField = call("POST", WS + "/tickets/query", alice, Map.of("typeCode", List.of("repair"),
                "fieldFilters", List.of(Map.of("field", "source", "value", "CS")), "page", 1, "size", 20));
        assertThat(byField.body().get("list").valueStream().map(n -> n.get("fields").get("source").get("value").asText()).distinct().toList())
                .containsExactly("CS");
        assertThat(call("POST", WS + "/tickets/query", token("dave"), Map.of("page", 1, "size", 20)).body().get("total").asLong())
                .as("无工单权限、非参与者：列表为空（fail-closed）").isZero();
        Res tl = call("POST", WS + "/tickets/" + no + "/comments", alice, Map.of("body", "已上门", "visibility", "INTERNAL"));
        assertThat(tl.status()).isEqualTo(200);
        List<String> titles = tl.body().valueStream().map(n -> n.get("title").asText()).toList();
        assertThat(titles).contains("创建工单", "领取", "受理：新建 → 处理中", "内部备注");
        assertThat(call("GET", WS + "/tickets/" + no + "/timeline", token("bob"), null).body().valueStream()
                .map(n -> n.get("title").asText()).toList()).as("报单人看不到内部备注").doesNotContain("内部备注");
        Res cards = call("POST", WS + "/ticket-stats/summary", alice, Map.of("typeCode", List.of("repair")));
        assertThat(cards.status()).isEqualTo(200);
        JsonNode open = cards.body().valueStream().filter(n -> "open".equals(n.get("key").asText())).findFirst().orElseThrow();
        assertThat(open.get("label").asText()).isEqualTo("未结");
        Map<String, Object> linked = json.convertValue(open.get("queryParams"), Map.class);
        linked.put("page", 1);
        linked.put("size", 200);
        assertThat(call("POST", WS + "/tickets/query", alice, linked).body().get("total").asLong()).as("卡与列表同口径")
                .isEqualTo(open.get("value").asLong());
        String queued = (String) seed.manifest().fixtures().get("ticketQueued");
        assertThat(call("POST", WS + "/tickets/" + queued + "/pool", alice, Map.of("reason", "x")).status()).as("还没人办，退回池 → 409").isEqualTo(409);
        assertThat(call("GET", WS + "/tickets/" + queued, token("dave"), null).status()).as("无工单权限、非参与者").isEqualTo(404);
    }

    @Test
    void healthReportsSchedulerAndOutbox() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(BASE + "/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode h = json.readTree(r.body());
        assertThat(h.get("components").get("scheduler").get("status").asText()).isEqualTo("UP");
        assertThat(h.get("components").get("outbox").get("details").has("due")).isTrue();
        HttpResponse<String> m = http.send(HttpRequest.newBuilder(URI.create(BASE + "/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(m.body()).contains("opskit_outbox_pending");
    }

    /** 定义 PUT：请求体即部署示例的定义文件（同形）；幂等 · 判权 · 代号与路径一致 · 形状坏按 422 而非 500。 */
    @Test
    void definitionsPublishOverHttpIdempotently() throws Exception {
        String admin = token("admin");
        java.nio.file.Path ex = java.nio.file.Path.of("../../deploy/opskit/definitions.example");
        Map<?, ?> flow = json.readValue(java.nio.file.Files.readString(ex.resolve("processes/repair_flow.json")).replace("repair_flow", "http_flow"), Map.class);
        Map<?, ?> type = json.readValue(java.nio.file.Files.readString(ex.resolve("ticket-types/repair.json"))
                .replace("\"repair\"", "\"http_repair\"").replace("repair_flow", "http_flow"), Map.class);

        assertThat(call("PUT", WS + "/processes/http_flow", token("viewer"), flow).status()).as("无 process:manage").isEqualTo(403);
        Res first = call("PUT", WS + "/processes/http_flow", admin, flow);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("created").asBoolean()).isTrue();
        Res again = call("PUT", WS + "/processes/http_flow", admin, flow);
        assertThat(again.body().get("created").asBoolean()).as("内容不变不出新版本").isFalse();
        assertThat(again.body().get("version").asText()).isEqualTo(first.body().get("version").asText());

        Res mismatch = call("PUT", WS + "/processes/other_code", admin, flow);
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.body().toString()).contains("opskit.validation.mismatch");
        Res malformed = call("PUT", WS + "/processes/http_flow", admin, Map.of("code", "http_flow", "statuses", "not-a-list"));
        assertThat(malformed.status()).as("形状坏：422 而非 500").isEqualTo(422);

        Res t = call("PUT", WS + "/ticket-types/http_repair", admin, type);
        assertThat(t.status()).isEqualTo(200);
        assertThat(t.body().get("created").asBoolean()).isTrue();
        assertThat(call("GET", WS + "/ticket-types", admin, null).body().toString()).contains("http_repair");
    }
}
