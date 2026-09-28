package ai.neargo.opskit.svc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 21/07 O4-2：错误键目录 opskit/docs/error-keys.json 与源码双向一致 —— 源码里每个错误键都有 zh / en / ar 三语和 HTTP 状态，
 * 目录里也不留源码已不用的死键。前端与接入方据此本地化，服务端响应只带键不带文案。
 */
class ErrorKeyCatalogTest {

    private static final Path ROOT = Path.of("..");
    /** 形如 "opskit.xxx" 或 "opskit.xxx:细节" 的字面量；后者冒号后是字段 / 取值细节，不属于键。 */
    private static final Pattern KEY = Pattern.compile("\"(opskit\\.[a-z0-9_.]+)[\":]");
    /** 不是错误键的同前缀字面量：配置项（opskit.bootstrap…，含拼接用的 "opskit.bootstrap.apps." 这类前缀）与指标名。 */
    private static final Set<String> NOT_ERROR_KEYS = Set.of("opskit.outbox.due", "opskit.outbox.failed", "opskit.outbox.pending");
    private static final String CONFIG_PREFIX = "opskit.bootstrap";
    private static final Set<Integer> STATUSES = Set.of(400, 401, 403, 404, 405, 409, 422, 429, 500, 503);

    @Test
    void everyKeyInSourceHasThreeLanguagesAndNoKeyIsDead() throws IOException {
        Set<String> inSource = keysInSource();
        JsonNode catalog = new ObjectMapper().readTree(ROOT.resolve("docs/error-keys.json").toFile());
        Set<String> inCatalog = new TreeSet<>(catalog.propertyNames());

        Set<String> missing = new TreeSet<>(inSource);
        missing.removeAll(inCatalog);
        Set<String> dead = new TreeSet<>(inCatalog);
        dead.removeAll(inSource);
        assertThat(missing).as("源码里的错误键没有登记到 docs/error-keys.json").isEmpty();
        assertThat(dead).as("docs/error-keys.json 里有源码已不用的键").isEmpty();

        Set<String> incomplete = new TreeSet<>();
        for (Map.Entry<String, JsonNode> e : catalog.properties()) {
            JsonNode v = e.getValue();
            boolean ok = STATUSES.contains(v.path("status").asInt()) && Stream.of("zh", "en", "ar").allMatch(l -> !v.path(l).asText("").isBlank());
            if (!ok) {
                incomplete.add(e.getKey());
            }
        }
        assertThat(incomplete).as("缺状态码或缺某种语言的文案").isEmpty();
    }

    static Set<String> keysInSource() throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/")).toList()) {
                Matcher m = KEY.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        out.removeAll(NOT_ERROR_KEYS);
        out.removeIf(k -> k.startsWith(CONFIG_PREFIX));
        return out;
    }
}
