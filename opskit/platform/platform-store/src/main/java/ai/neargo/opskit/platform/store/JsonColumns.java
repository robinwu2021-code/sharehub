package ai.neargo.opskit.platform.store;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 列的读写。只处理「整存整取」的小结构（多语种名称、属性、标签集合、权限集合），
 * 这些列不参与筛选（21/04 §一）。用 Jackson 3（Boot 4 自带）；Jackson 2 由架构测试 A10 禁止。
 */
public final class JsonColumns {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> OBJECTS = new TypeReference<>() { };

    private JsonColumns() {
    }

    public static String write(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    public static Map<String, Object> readMap(String json) {
        return json == null ? Map.of() : MAPPER.readValue(json, MAP);
    }

    public static Map<String, String> readText(String json) {
        return json == null ? Map.of() : MAPPER.readValue(json, TEXT);
    }

    public static List<Map<String, Object>> readObjects(String json) {
        return json == null ? List.of() : MAPPER.readValue(json, OBJECTS);
    }

    /** 读成有序集合：写回时顺序稳定，比较「有没有变化」才可靠。 */
    public static Set<String> readSet(String json) {
        return json == null ? Set.of() : new TreeSet<>(MAPPER.readValue(json, STRINGS));
    }
}
