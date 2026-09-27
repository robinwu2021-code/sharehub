package ai.neargo.opskit.process.api.model;

import java.util.Map;

/** 外部引用（业务对象）：类型 + 编号 + 展示快照。服务不认识业务，只存它（21/02 §1.1 R2）。 */
public record SubjectRef(String type, String ref, Map<String, Object> display) {
    public SubjectRef {
        display = display == null ? Map.of() : Map.copyOf(display);
    }
}
