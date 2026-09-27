package ai.neargo.opskit.process.api.model;

import java.util.Map;

/** @param fields 本次顺带提交的字段（先合并进实例字段，再校验必填与条件） */
public record TransitionRequest(Map<String, Object> fields, String comment) {
    public TransitionRequest {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public static TransitionRequest empty() {
        return new TransitionRequest(Map.of(), null);
    }
}
