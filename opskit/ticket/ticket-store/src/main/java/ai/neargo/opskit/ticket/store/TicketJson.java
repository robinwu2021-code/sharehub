package ai.neargo.opskit.ticket.store;

import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 工单模块的 JSON 列读写（按分层规则不能依赖流程 / 平台 store）。 */
public final class TicketJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() { };

    private TicketJson() {
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
