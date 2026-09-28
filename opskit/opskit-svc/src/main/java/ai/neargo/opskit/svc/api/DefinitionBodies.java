package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * 定义类 PUT 的请求体：直接是定义的规范化 JSON（与存储、启动引导的定义文件同形），另可带 note。
 * 形状解析失败按 422 opskit.validation 报，不让 ClassCastException 之类漏成 500。
 */
final class DefinitionBodies {

    private DefinitionBodies() {
    }

    record PublishDto(String version, boolean created) {
    }

    static <T> T parse(String pathCode, JsonNode body, Function<String, T> codec, Function<T, String> codeOf, String what) {
        T def;
        try {
            def = codec.apply(body.toString());
        } catch (OpsKitException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OpsKitException(CommonErrors.VALIDATION, "malformed " + what + ": " + e.getMessage(), Map.of("code", pathCode),
                    List.of(new FieldError("body", "opskit.validation.invalid")));
        }
        if (!pathCode.equals(codeOf.apply(def))) {
            throw new OpsKitException(CommonErrors.VALIDATION, what + " code differs from path", Map.of("code", pathCode),
                    List.of(new FieldError("code", "opskit.validation.mismatch")));
        }
        return def;
    }

    static String note(JsonNode body) {
        JsonNode n = body.get("note");
        return n == null || n.isNull() ? null : n.asText();
    }

    static String channel(Actor actor) {
        return actor.isAppItself() ? "API" : "CONSOLE";
    }
}
