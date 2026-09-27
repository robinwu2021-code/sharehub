package ai.neargo.opskit.process.store;

import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 流程模块的 JSON 列读写（与平台的 JsonColumns 同作用；按分层规则流程不能依赖平台 store）。 */
public final class ProcessJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() { };

    private ProcessJson() {
    }

    public static String write(Object v) {
        return v == null ? null : MAPPER.writeValueAsString(v);
    }

    public static Map<String, Object> readMap(String json) {
        return json == null ? Map.of() : MAPPER.readValue(json, MAP);
    }

    public static List<Object> readList(String json) {
        return json == null ? List.of() : MAPPER.readValue(json, LIST);
    }
}
