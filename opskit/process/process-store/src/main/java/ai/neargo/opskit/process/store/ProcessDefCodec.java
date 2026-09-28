package ai.neargo.opskit.process.store;

import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.condition.Op;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.ActionDef;
import ai.neargo.opskit.process.api.definition.ApprovalMode;
import ai.neargo.opskit.process.api.definition.ApprovalRules;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.Requires;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 流程定义 ⇄ JSON。api 包不许用 Jackson 注解（A3），所以显式映射；输出是**规范化**的：
 * 对象键一律排序、集合转成有序数组 —— 同一份定义无论集合怎么写，JSON 与版本哈希都相同。
 *
 * <p>注意：库里的 JSON 列会被数据库改写（MySQL 重排键、PG JSONB 排序加空格），所以版本哈希永远基于
 * 「解析后再用本类序列化」的结果，不基于库里读出的原文。
 */
public final class ProcessDefCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private ProcessDefCodec() {
    }

    public static String toCanonicalJson(ProcessDef d) {
        return JSON.writeValueAsString(toMap(d));
    }

    /** 多语种名称单独序列化（proc_definition.name 列）。 */
    public static String textJson(LocalizedText t) {
        return t == null ? null : JSON.writeValueAsString(new TreeMap<>(t.values()));
    }

    public static ProcessDef fromJson(String json) {
        return fromMap(JSON.readValue(json, MAP));
    }

    // ── 写 ────────────────────────────────────────────────
    static Map<String, Object> toMap(ProcessDef d) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", d.code());
        put(m, "name", text(d.name()));
        m.put("initialStatus", d.initialStatus());
        m.put("statuses", d.statuses().stream().map(ProcessDefCodec::status).toList());
        m.put("transitions", d.transitions().stream().map(ProcessDefCodec::transition).toList());
        m.put("participantRoles", sorted(d.participantRoles()));
        return m;
    }

    private static Map<String, Object> status(StatusDef s) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", s.code());
        put(m, "name", text(s.name()));
        m.put("category", s.category().name());
        put(m, "gate", s.gate() == null ? null : gate(s.gate()));
        return m;
    }

    private static Map<String, Object> gate(GateDef g) {
        Map<String, Object> m = new TreeMap<>();
        switch (g) {
            case GateDef.Approval a -> {
                m.put("type", "APPROVAL");
                m.put("steps", a.steps().stream().map(ProcessDefCodec::step).toList());
                m.put("rules", new TreeMap<>(Map.of("selfApproval", a.rules().selfApproval(), "crossStepExclusive", a.rules().crossStepExclusive())));
                put(m, "onApproved", a.onApproved());
                put(m, "onRejected", a.onRejected());
                put(m, "onWithdrawn", a.onWithdrawn());
            }
            case GateDef.External e -> {
                m.put("type", "EXTERNAL");
                put(m, "topic", e.topic());
                put(m, "timeout", duration(e.timeout()));
                put(m, "onConfirmed", e.onConfirmed());
                put(m, "onRejected", e.onRejected());
                put(m, "onTimeout", e.onTimeout());
            }
            case GateDef.Timer t -> {
                m.put("type", "TIMER");
                put(m, "after", duration(t.after()));
                put(m, "onElapsed", t.onElapsed());
            }
        }
        return m;
    }

    private static Map<String, Object> step(ApprovalStepDef s) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", s.code());
        put(m, "name", text(s.name()));
        put(m, "when", condition(s.when()));
        m.put("approvers", approvers(s.approvers()));
        m.put("mode", s.mode().name());
        put(m, "due", duration(s.due()));
        return m;
    }

    private static Map<String, Object> approvers(ApproverRef r) {
        Map<String, Object> m = new TreeMap<>();
        m.put("kind", r.kind().name());
        m.put("values", r.values());
        return m;
    }

    private static Map<String, Object> transition(TransitionDef t) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", t.code());
        put(m, "name", text(t.name()));
        m.put("from", sorted(t.from()));
        m.put("to", t.to());
        if (!t.who().isDefault()) {
            Map<String, Object> w = new TreeMap<>();
            w.put("participantRoles", sorted(t.who().participantRoles()));
            w.put("roles", sorted(t.who().roles()));
            w.put("groups", sorted(t.who().groups()));
            w.put("perms", sorted(t.who().perms()));
            m.put("who", w);
        }
        put(m, "when", condition(t.when()));
        if (!t.requires().fields().isEmpty() || t.requires().comment()) {
            m.put("requires", new TreeMap<>(Map.of("fields", sorted(t.requires().fields()), "comment", t.requires().comment())));
        }
        if (!t.actions().isEmpty()) {
            m.put("actions", t.actions().stream().map(ProcessDefCodec::action).toList());
        }
        if (t.system()) {
            m.put("system", true);
        }
        return m;
    }

    private static Map<String, Object> action(ActionDef a) {
        Map<String, Object> m = new TreeMap<>();
        switch (a) {
            case ActionDef.AssignTo x -> {
                m.put("type", "ASSIGN_TO");
                m.put("candidates", approvers(x.candidates()));
            }
            case ActionDef.SetField x -> {
                m.put("type", "SET_FIELD");
                m.put("field", x.field());
                m.put("value", x.value());
            }
            case ActionDef.AddParticipant x -> {
                m.put("type", "ADD_PARTICIPANT");
                m.put("role", x.role());
                m.put("fromField", x.fromField());
            }
            case ActionDef.ClearAssignee x -> m.put("type", "CLEAR_ASSIGNEE");
        }
        return m;
    }

    static Object condition(Condition c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> m = new TreeMap<>();
        switch (c) {
            case Condition.Compare x -> {
                m.put("field", x.field());
                m.put("op", x.op().name());
                if (x.value() != null) {
                    m.put("value", x.value());
                }
            }
            case Condition.All x -> m.put("all", x.items().stream().map(ProcessDefCodec::condition).toList());
            case Condition.Any x -> m.put("any", x.items().stream().map(ProcessDefCodec::condition).toList());
        }
        return m;
    }

    private static Map<String, String> text(LocalizedText t) {
        return t == null ? null : new TreeMap<>(t.values());
    }

    private static String duration(Duration d) {
        return d == null ? null : d.toString();
    }

    private static List<String> sorted(Collection<String> c) {
        return new ArrayList<>(new TreeSet<>(c));
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    // ── 读 ────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    static ProcessDef fromMap(Map<String, Object> m) {
        return new ProcessDef((String) m.get("code"), readText(m.get("name")), (String) m.get("initialStatus"),
                list(m.get("statuses")).stream().map(x -> readStatus((Map<String, Object>) x)).toList(),
                list(m.get("transitions")).stream().map(x -> readTransition((Map<String, Object>) x)).toList(),
                strings(m.get("participantRoles")));
    }

    @SuppressWarnings("unchecked")
    private static StatusDef readStatus(Map<String, Object> m) {
        return new StatusDef((String) m.get("code"), readText(m.get("name")), StatusCategory.valueOf((String) m.get("category")),
                m.get("gate") == null ? null : readGate((Map<String, Object>) m.get("gate")));
    }

    @SuppressWarnings("unchecked")
    private static GateDef readGate(Map<String, Object> m) {
        return switch ((String) m.get("type")) {
            case "APPROVAL" -> {
                Map<String, Object> r = (Map<String, Object>) m.getOrDefault("rules", Map.of());
                yield new GateDef.Approval(list(m.get("steps")).stream().map(x -> readStep((Map<String, Object>) x)).toList(),
                        new ApprovalRules(Boolean.TRUE.equals(r.get("selfApproval")), !Boolean.FALSE.equals(r.get("crossStepExclusive"))),
                        (String) m.get("onApproved"), (String) m.get("onRejected"), (String) m.get("onWithdrawn"));
            }
            case "EXTERNAL" -> new GateDef.External((String) m.get("topic"), readDuration(m.get("timeout")),
                    (String) m.get("onConfirmed"), (String) m.get("onRejected"), (String) m.get("onTimeout"));
            case "TIMER" -> new GateDef.Timer(readDuration(m.get("after")), (String) m.get("onElapsed"));
            default -> throw new IllegalArgumentException("unknown gate type " + m.get("type"));
        };
    }

    @SuppressWarnings("unchecked")
    private static ApprovalStepDef readStep(Map<String, Object> m) {
        return new ApprovalStepDef((String) m.get("code"), readText(m.get("name")), readCondition(m.get("when")),
                readApprovers((Map<String, Object>) m.get("approvers")), ApprovalMode.valueOf((String) m.getOrDefault("mode", "ANY")),
                readDuration(m.get("due")));
    }

    private static ApproverRef readApprovers(Map<String, Object> m) {
        // 保留原顺序：成员列表的先后可能有业务含义，不能像集合那样排序
        return new ApproverRef(ApproverKind.valueOf((String) m.get("kind")), list(m.get("values")).stream().map(String::valueOf).toList());
    }

    @SuppressWarnings("unchecked")
    private static TransitionDef readTransition(Map<String, Object> m) {
        Map<String, Object> w = (Map<String, Object>) m.get("who");
        Map<String, Object> r = (Map<String, Object>) m.get("requires");
        List<ActionDef> actions = list(m.get("actions")).stream().map(x -> readAction((Map<String, Object>) x)).toList();
        return new TransitionDef((String) m.get("code"), readText(m.get("name")), strings(m.get("from")), (String) m.get("to"),
                w == null ? null : new Who(strings(w.get("participantRoles")), strings(w.get("roles")), strings(w.get("groups")), strings(w.get("perms"))),
                readCondition(m.get("when")),
                r == null ? null : new Requires(strings(r.get("fields")), Boolean.TRUE.equals(r.get("comment"))),
                actions, Boolean.TRUE.equals(m.get("system")));
    }

    @SuppressWarnings("unchecked")
    private static ActionDef readAction(Map<String, Object> m) {
        return switch ((String) m.get("type")) {
            case "ASSIGN_TO" -> new ActionDef.AssignTo(readApprovers((Map<String, Object>) m.get("candidates")));
            case "SET_FIELD" -> new ActionDef.SetField((String) m.get("field"), m.get("value"));
            case "ADD_PARTICIPANT" -> new ActionDef.AddParticipant((String) m.get("role"), (String) m.get("fromField"));
            case "CLEAR_ASSIGNEE" -> new ActionDef.ClearAssignee();
            default -> throw new IllegalArgumentException("unknown action type " + m.get("type"));
        };
    }

    @SuppressWarnings("unchecked")
    static Condition readCondition(Object o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        if (m.containsKey("all")) {
            return new Condition.All(list(m.get("all")).stream().map(ProcessDefCodec::readCondition).toList());
        }
        if (m.containsKey("any")) {
            return new Condition.Any(list(m.get("any")).stream().map(ProcessDefCodec::readCondition).toList());
        }
        return new Condition.Compare((String) m.get("field"), Op.valueOf((String) m.get("op")), m.get("value"));
    }

    @SuppressWarnings("unchecked")
    private static LocalizedText readText(Object o) {
        return o == null || ((Map<String, String>) o).isEmpty() ? null : new LocalizedText((Map<String, String>) o);
    }

    private static Duration readDuration(Object o) {
        return o == null ? null : Duration.parse((String) o);
    }

    private static List<?> list(Object o) {
        return o == null ? List.of() : (List<?>) o;
    }

    private static Set<String> strings(Object o) {
        Set<String> out = new TreeSet<>();
        list(o).forEach(x -> out.add(String.valueOf(x)));
        return out;
    }
}
