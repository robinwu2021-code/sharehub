package ai.neargo.opskit.process.store;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 审批模板 ⇄ 规范化 JSON（键排序、步骤保序）。版本 = 该 JSON 的哈希，所以同一份模板重复发布不产生新版本。
 * 与 {@link ProcessDefCodec} 同理：哈希只基于本类的输出，不基于库里读回的原文。
 */
public final class ApprovalTemplateCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private ApprovalTemplateCodec() {
    }

    public static String toCanonicalJson(ApprovalTemplate t) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", t.code());
        m.put("name", text(t.name()));
        m.put("rules", new TreeMap<>(Map.of("selfApproval", t.rules().selfApproval(), "crossStepExclusive", t.rules().crossStepExclusive())));
        List<Object> steps = new ArrayList<>();
        for (ApprovalTemplate.Step s : t.steps()) {
            Map<String, Object> sm = new TreeMap<>();
            sm.put("code", s.code());
            sm.put("name", text(s.name()));
            sm.put("approver", new TreeMap<>(Map.of("kind", s.approver().kind().name(), "values", s.approver().values())));
            sm.put("mode", s.mode());
            if (s.when() != null) {
                Map<String, Object> w = new TreeMap<>();
                w.put("field", s.when().field());
                w.put("op", s.when().op());
                if (s.when().value() != null) {
                    w.put("value", s.when().value());
                }
                sm.put("when", w);
            }
            if (s.dueHours() != null) {
                sm.put("dueHours", s.dueHours());
            }
            steps.add(sm);
        }
        m.put("steps", steps);
        // 只在有表单时写：没有表单的旧模板 JSON 与版本哈希保持不变
        if (!t.fields().isEmpty()) {
            m.put("fields", t.fields().stream().map(f -> {
                Map<String, Object> fm = new TreeMap<>();
                fm.put("code", f.code());
                fm.put("name", text(f.name()));
                fm.put("type", f.type());
                fm.put("required", f.required());
                if (!f.options().isEmpty()) {
                    fm.put("options", f.options().stream().map(o -> {
                        Map<String, Object> om = new TreeMap<>();
                        om.put("code", o.code());
                        om.put("name", text(o.name()));
                        return om;
                    }).toList());
                }
                return fm;
            }).toList());
        }
        return JSON.writeValueAsString(m);
    }

    @SuppressWarnings("unchecked")
    public static ApprovalTemplate fromJson(String json) {
        Map<String, Object> m = JSON.readValue(json, MAP);
        List<ApprovalTemplate.Step> steps = new ArrayList<>();
        for (Object o : (List<Object>) m.get("steps")) {
            Map<String, Object> s = (Map<String, Object>) o;
            Map<String, Object> a = (Map<String, Object>) s.get("approver");
            Map<String, Object> w = (Map<String, Object>) s.get("when");
            steps.add(new ApprovalTemplate.Step((String) s.get("code"), readText(s.get("name")),
                    new ApprovalTemplate.Approver(ApproverKind.valueOf((String) a.get("kind")), (List<String>) a.get("values")), (String) s.get("mode"),
                    w == null ? null : new ApprovalTemplate.When((String) w.get("field"), (String) w.get("op"), (String) w.get("value")),
                    s.get("dueHours") == null ? null : ((Number) s.get("dueHours")).intValue()));
        }
        Map<String, Object> r = (Map<String, Object>) m.get("rules");
        List<ApprovalTemplate.FormField> fields = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("fields", List.of())) {
            Map<String, Object> f = (Map<String, Object>) o;
            List<ApprovalTemplate.Option> options = new ArrayList<>();
            for (Object x : (List<Object>) f.getOrDefault("options", List.of())) {
                Map<String, Object> om = (Map<String, Object>) x;
                options.add(new ApprovalTemplate.Option((String) om.get("code"), readText(om.get("name"))));
            }
            fields.add(new ApprovalTemplate.FormField((String) f.get("code"), readText(f.get("name")), (String) f.get("type"),
                    Boolean.TRUE.equals(f.get("required")), options));
        }
        return new ApprovalTemplate((String) m.get("code"), readText(m.get("name")), steps,
                new ApprovalTemplate.Rules(Boolean.TRUE.equals(r.get("selfApproval")), Boolean.TRUE.equals(r.get("crossStepExclusive"))), fields);
    }

    private static Map<String, String> text(LocalizedText t) {
        return t == null ? Map.of() : new TreeMap<>(t.values());
    }

    @SuppressWarnings("unchecked")
    private static LocalizedText readText(Object o) {
        return o == null || ((Map<String, String>) o).isEmpty() ? null : new LocalizedText((Map<String, String>) o);
    }
}
