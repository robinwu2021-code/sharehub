package ai.neargo.opskit.svc;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 21/07 O4-2：OpenAPI 落盘 —— 按层拆成 platform / process / ticket 三份，写到 opskit/docs/openapi/。
 *
 * <p>默认是比对：已提交的文件与本次生成的不一致即红（改了接口却没更新契约文件）。
 * 更新契约：{@code mvn -pl opskit-svc test -Dtest=OpenApiExportTest -Dopskit.openapi.write=true}。
 * springdoc 只在 test 作用域，生产进程里没有 /v3/api-docs。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:opskit-openapi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "opskit.crypto.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class OpenApiExportTest {

    /** 控制器（springdoc 默认标签）→ 所属层。新增控制器必须登记，否则测试红，避免悄悄漏出契约。 */
    private static final Map<String, String> LAYER_OF_TAG = Map.ofEntries(
            Map.entry("directory-controller", "platform"),
            Map.entry("todo-task-controller", "process"),
            Map.entry("approval-controller", "process"),
            Map.entry("approval-template-controller", "process"),
            Map.entry("sla-policy-controller", "process"),
            Map.entry("instance-controller", "process"),
            Map.entry("ticket-controller", "ticket"),
            Map.entry("ticket-stats-controller", "ticket"),
            Map.entry("process-definition-controller", "process"),
            Map.entry("confirmation-controller", "process"),
            Map.entry("ticket-type-definition-controller", "ticket"));

    private static final Path OUT = Path.of("..", "docs", "openapi");

    private final ObjectMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    @LocalServerPort
    int port;

    @Test
    void committedContractsMatchTheRunningService() throws Exception {
        String body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        JsonNode full = json.readTree(body);
        assertThat(full.has("openapi")).as("/v3/api-docs 没返回 OpenAPI 文档：%s", body).isTrue();

        Set<String> unknownTags = new TreeSet<>();
        boolean write = Boolean.getBoolean("opskit.openapi.write");
        List<String> stale = new java.util.ArrayList<>();
        for (String layer : List.of("platform", "process", "ticket")) {
            String rendered = render(full, layer, unknownTags);
            Path file = OUT.resolve(layer + ".json");
            if (write) {
                Files.createDirectories(OUT);
                Files.writeString(file, rendered, StandardCharsets.UTF_8);
            } else if (!Files.exists(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(rendered)) {
                stale.add(file.normalize().toString());
            }
        }
        assertThat(unknownTags).as("新控制器没有登记到 LAYER_OF_TAG").isEmpty();
        assertThat(stale).as("契约文件过期：带 -Dopskit.openapi.write=true 重跑本测试并提交").isEmpty();
    }

    /** 取该层的路径，再按 $ref 闭包带上用到的 schema；去掉随端口变化的 servers，键全排序保证稳定。 */
    private String render(JsonNode full, String layer, Set<String> unknownTags) throws Exception {
        ObjectNode doc = json.createObjectNode();
        doc.put("openapi", full.get("openapi").asText());
        ObjectNode info = doc.putObject("info");
        info.put("title", "OpsKit " + layer);
        info.put("version", "v1");
        ObjectNode paths = doc.putObject("paths");
        for (Map.Entry<String, JsonNode> p : full.get("paths").properties()) {
            ObjectNode ops = json.createObjectNode();
            for (Map.Entry<String, JsonNode> op : p.getValue().properties()) {
                String tag = op.getValue().path("tags").path(0).asText("");
                String owner = LAYER_OF_TAG.get(tag);
                if (owner == null) {
                    if (!p.getKey().startsWith("/dev/")) {
                        unknownTags.add(tag + " " + p.getKey());
                    }
                } else if (owner.equals(layer)) {
                    ops.set(op.getKey(), op.getValue());
                }
            }
            if (!ops.isEmpty()) {
                paths.set(p.getKey(), ops);
            }
        }
        JsonNode schemas = full.path("components").path("schemas");
        Set<String> used = new TreeSet<>();
        Deque<JsonNode> todo = new ArrayDeque<>(List.of(paths));
        while (!todo.isEmpty()) {
            JsonNode n = todo.pop();
            if (n.isObject() && n.has("$ref")) {
                String name = n.get("$ref").asText().replace("#/components/schemas/", "");
                if (used.add(name) && schemas.has(name)) {
                    todo.push(schemas.get(name));
                }
            }
            n.forEach(todo::push);
        }
        if (!used.isEmpty()) {
            ObjectNode out = doc.putObject("components").putObject("schemas");
            used.forEach(name -> out.set(name, schemas.get(name)));
        }
        Object sorted = json.treeToValue(doc, Object.class);
        return json.writeValueAsString(sorted) + "\n";
    }
}
