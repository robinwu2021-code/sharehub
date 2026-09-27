package ai.neargo.opskit.process.api.model;

import ai.neargo.opskit.process.api.definition.StatusCategory;
import java.util.Map;
import java.util.Set;

/** 当前操作人可走的一条迁移（21/06 §七 Action）：前端只渲染，不维护迁移表。 */
public record ActionView(String code, Map<String, String> name, StatusCategory toCategory, Set<String> requiresFields, boolean requiresComment) {
}
