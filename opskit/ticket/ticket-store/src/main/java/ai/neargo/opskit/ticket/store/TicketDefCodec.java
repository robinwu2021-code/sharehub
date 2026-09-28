package ai.neargo.opskit.ticket.store;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.FieldType;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.OptionSetDef;
import ai.neargo.opskit.ticket.api.definition.Options;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 工单类型 / 选项集 ⇄ 规范化 JSON（键排序、列表保序）。版本 = 该 JSON 的哈希；api 包不许用 Jackson 注解（A3），所以显式映射。
 * 与流程的编解码同理：哈希只基于本类的输出，不基于库里读回的原文（JSON 列会被数据库改写）。
 */
public final class TicketDefCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private TicketDefCodec() {
    }

    // ── 工单类型 ────────────────────────────────────────────

    public static String toCanonicalJson(TicketTypeDef t) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", t.code());
        m.put("name", text(t.name()));
        m.put("processCode", t.processCode());
        m.put("fields", t.fields().stream().map(TicketDefCodec::field).toList());
        put(m, "priorityOptions", options(t.priorityOptions()));
        put(m, "resolutionOptions", options(t.resolutionOptions()));
        put(m, "numberPrefix", t.numberPrefix());
        Map<String, Object> a = new TreeMap<>();
        a.put("strategy", t.assignment().strategy().name());
        put(a, "group", t.assignment().group());
        put(a, "field", t.assignment().field());
        put(a, "externalTimeoutSec", t.assignment().externalTimeoutSec());
        put(a, "fallback", t.assignment().fallback() == null ? null : t.assignment().fallback().name());
        m.put("assignment", a);
        m.put("slaPolicies", t.slaPolicies());
        m.put("scopeFrom", t.scopeFrom().stream().map(s -> new TreeMap<>(Map.of("field", s.field(), "dimension", s.dimension()))).toList());
        put(m, "dedupe", t.dedupe() == null ? null : Map.of("fields", t.dedupe().fields()));
        put(m, "mergeTransition", t.mergeTransition());
        return JSON.writeValueAsString(m);
    }

    @SuppressWarnings("unchecked")
    public static TicketTypeDef typeFromJson(String json) {
        Map<String, Object> m = JSON.readValue(json, MAP);
        Map<String, Object> a = (Map<String, Object>) m.get("assignment");
        AssignmentDef assignment = new AssignmentDef(AssignmentDef.Strategy.valueOf((String) a.get("strategy")), (String) a.get("group"),
                (String) a.get("field"), a.get("externalTimeoutSec") == null ? null : ((Number) a.get("externalTimeoutSec")).intValue(),
                a.get("fallback") == null ? null : AssignmentDef.Strategy.valueOf((String) a.get("fallback")));
        List<TicketTypeDef.ScopeFrom> scopes = new ArrayList<>();
        for (Object o : (List<Object>) m.get("scopeFrom")) {
            Map<String, Object> s = (Map<String, Object>) o;
            scopes.add(new TicketTypeDef.ScopeFrom((String) s.get("field"), (String) s.get("dimension")));
        }
        Map<String, Object> dedupe = (Map<String, Object>) m.get("dedupe");
        return new TicketTypeDef((String) m.get("code"), readText(m.get("name")), (String) m.get("processCode"),
                ((List<Object>) m.get("fields")).stream().map(o -> readField((Map<String, Object>) o)).toList(),
                readOptions(m.get("priorityOptions")), readOptions(m.get("resolutionOptions")), (String) m.get("numberPrefix"), assignment,
                (List<String>) m.get("slaPolicies"), scopes, dedupe == null ? null : new TicketTypeDef.Dedupe((List<String>) dedupe.get("fields")),
                (String) m.get("mergeTransition"));
    }

    // ── 选项集 ──────────────────────────────────────────────

    public static String toCanonicalJson(OptionSetDef s) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", s.code());
        m.put("name", text(s.name()));
        m.put("options", s.options().stream().map(TicketDefCodec::option).toList());
        return JSON.writeValueAsString(m);
    }

    @SuppressWarnings("unchecked")
    public static OptionSetDef optionSetFromJson(String json) {
        Map<String, Object> m = JSON.readValue(json, MAP);
        return new OptionSetDef((String) m.get("code"), readText(m.get("name")),
                ((List<Object>) m.get("options")).stream().map(o -> readOption((Map<String, Object>) o)).toList());
    }

    public static String textJson(LocalizedText t) {
        return t == null ? null : JSON.writeValueAsString(new TreeMap<>(t.values()));
    }

    // ── 内部 ────────────────────────────────────────────────

    private static Map<String, Object> field(FieldDef f) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", f.code());
        m.put("name", text(f.name()));
        m.put("type", f.type() == null ? null : f.type().name());
        m.put("required", f.required());
        m.put("onCreate", f.onCreate());
        m.put("filterable", f.filterable());
        put(m, "options", options(f.options()));
        if (f.multiple()) {
            m.put("multiple", true);
        }
        put(m, "refType", f.refType());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static FieldDef readField(Map<String, Object> m) {
        return new FieldDef((String) m.get("code"), readText(m.get("name")), m.get("type") == null ? null : FieldType.valueOf((String) m.get("type")),
                Boolean.TRUE.equals(m.get("required")), Boolean.TRUE.equals(m.get("onCreate")), Boolean.TRUE.equals(m.get("filterable")),
                readOptions(m.get("options")), Boolean.TRUE.equals(m.get("multiple")), (String) m.get("refType"));
    }

    private static Map<String, Object> options(Options o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> m = new TreeMap<>();
        if (o.optionSet() != null) {
            m.put("optionSet", o.optionSet());
        } else {
            m.put("inline", o.inline().stream().map(TicketDefCodec::option).toList());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Options readOptions(Object o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        if (m.get("optionSet") != null) {
            return Options.ref((String) m.get("optionSet"));
        }
        return Options.of(((List<Object>) m.getOrDefault("inline", List.of())).stream().map(x -> readOption((Map<String, Object>) x)).toList());
    }

    private static Map<String, Object> option(OptionDef o) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", o.code());
        m.put("name", text(o.name()));
        if (!o.enabled()) {
            m.put("enabled", false);
        }
        return m;
    }

    private static OptionDef readOption(Map<String, Object> m) {
        return new OptionDef((String) m.get("code"), readText(m.get("name")), !Boolean.FALSE.equals(m.get("enabled")));
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private static Map<String, String> text(LocalizedText t) {
        return t == null ? Map.of() : new TreeMap<>(t.values());
    }

    @SuppressWarnings("unchecked")
    private static LocalizedText readText(Object o) {
        return o == null || ((Map<String, String>) o).isEmpty() ? null : new LocalizedText((Map<String, String>) o);
    }
}
