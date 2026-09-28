package ai.neargo.opskit.process.store;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.sla.SlaPolicyDef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SLA 策略 ⇄ 规范化 JSON（键排序、集合排序、列表保序）；版本 = 该 JSON 的哈希。 */
public final class SlaPolicyCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private SlaPolicyCodec() {
    }

    public static String toCanonicalJson(SlaPolicyDef p) {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", p.code());
        m.put("name", text(p.name()));
        if (p.warnAtPercent() != null) {
            m.put("warnAtPercent", p.warnAtPercent());
        }
        m.put("metrics", p.metrics().stream().map(x -> {
            Map<String, Object> mm = new TreeMap<>();
            mm.put("code", x.code());
            mm.put("name", text(x.name()));
            if (x.start() != null) {
                mm.put("start", trigger(x.start()));
            }
            if (x.pause() != null) {
                mm.put("pause", new TreeMap<>(Map.of("categories", names(x.pause().categories()), "statuses", new TreeSet<>(x.pause().statuses()))));
            }
            if (x.stop() != null) {
                mm.put("stop", trigger(x.stop()));
            }
            return mm;
        }).toList());
        m.put("targets", p.targets().stream().map(t -> {
            Map<String, Object> tm = new TreeMap<>();
            Object when = ProcessDefCodec.condition(t.when());
            if (when != null) {
                tm.put("when", when);
            }
            tm.put("targets", new TreeMap<>(t.targets().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()))));
            if (t.calendar() != null) {
                tm.put("calendar", t.calendar());
            }
            return tm;
        }).toList());
        return JSON.writeValueAsString(m);
    }

    @SuppressWarnings("unchecked")
    public static SlaPolicyDef fromJson(String json) {
        Map<String, Object> m = JSON.readValue(json, MAP);
        List<SlaPolicyDef.Metric> metrics = new ArrayList<>();
        for (Object o : (List<Object>) m.get("metrics")) {
            Map<String, Object> x = (Map<String, Object>) o;
            Map<String, Object> pause = (Map<String, Object>) x.get("pause");
            metrics.add(new SlaPolicyDef.Metric((String) x.get("code"), readText(x.get("name")), readTrigger(x.get("start")),
                    pause == null ? null : new SlaPolicyDef.Pause(categories(pause.get("categories")), Set.copyOf((Collection<String>) pause.get("statuses"))),
                    readTrigger(x.get("stop"))));
        }
        List<SlaPolicyDef.Target> targets = new ArrayList<>();
        for (Object o : (List<Object>) m.get("targets")) {
            Map<String, Object> t = (Map<String, Object>) o;
            Map<String, Duration> ds = new TreeMap<>();
            ((Map<String, String>) t.get("targets")).forEach((k, v) -> ds.put(k, Duration.parse(v)));
            targets.add(new SlaPolicyDef.Target(ProcessDefCodec.readCondition(t.get("when")), ds, (String) t.get("calendar")));
        }
        return new SlaPolicyDef((String) m.get("code"), readText(m.get("name")), metrics, targets,
                m.get("warnAtPercent") == null ? null : ((Number) m.get("warnAtPercent")).intValue());
    }

    private static Map<String, Object> trigger(SlaPolicyDef.Trigger t) {
        Map<String, Object> m = new TreeMap<>();
        m.put("onCreate", t.onCreate());
        m.put("categories", names(t.categories()));
        m.put("transitions", new TreeSet<>(t.transitions()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static SlaPolicyDef.Trigger readTrigger(Object o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        return new SlaPolicyDef.Trigger(Boolean.TRUE.equals(m.get("onCreate")), categories(m.get("categories")), Set.copyOf((Collection<String>) m.get("transitions")));
    }

    private static TreeSet<String> names(Set<StatusCategory> cs) {
        return cs.stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    @SuppressWarnings("unchecked")
    private static Set<StatusCategory> categories(Object o) {
        return o == null ? Set.of() : ((Collection<String>) o).stream().map(StatusCategory::valueOf).collect(Collectors.toSet());
    }

    private static Map<String, String> text(LocalizedText t) {
        return t == null ? Map.of() : new TreeMap<>(t.values());
    }

    @SuppressWarnings("unchecked")
    private static LocalizedText readText(Object o) {
        return o == null || ((Map<String, String>) o).isEmpty() ? null : new LocalizedText((Map<String, String>) o);
    }
}
