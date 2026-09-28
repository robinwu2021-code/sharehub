package ai.neargo.opskit.ticket.api.definition;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import java.util.List;

/**
 * 工单类型（21/02 §七 · 21/01 TKT-01）。定义是数据：账户级模板 + 空间级覆盖；工单没有自己的状态机，状态经 {@code processCode} 的实例迁移。
 *
 * @param numberPrefix     工单号前缀（如 {@code RP}），空 = {@code TK}
 * @param scopeFrom        范围标签的来源字段：字段值 → {@code dimension:值}
 * @param dedupe           去重键由哪些字段拼出（建单也可直接给 dedupeKey）；空 = 不去重
 * @param mergeTransition  被并单走的迁移（流程里必须存在）；空 = 本类型不支持合并
 */
public record TicketTypeDef(String code, LocalizedText name, String processCode, List<FieldDef> fields, Options priorityOptions,
        Options resolutionOptions, String numberPrefix, AssignmentDef assignment, List<String> slaPolicies, List<ScopeFrom> scopeFrom,
        Dedupe dedupe, String mergeTransition) {

    public TicketTypeDef {
        fields = fields == null ? List.of() : List.copyOf(fields);
        slaPolicies = slaPolicies == null ? List.of() : List.copyOf(slaPolicies);
        scopeFrom = scopeFrom == null ? List.of() : List.copyOf(scopeFrom);
        assignment = assignment == null ? AssignmentDef.MANUAL : assignment;
    }

    public record ScopeFrom(String field, String dimension) {
    }

    public record Dedupe(List<String> fields) {
        public Dedupe {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public java.util.Optional<FieldDef> field(String code) {
        return fields.stream().filter(f -> f.code().equals(code)).findFirst();
    }
}
