package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.condition.Op;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.sla.SlaPolicyDef;
import ai.neargo.opskit.process.core.app.SlaPolicyAppService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SLA 策略（21/06 §四 · 21/02 §6.5）。线上形状：目标时长用分钟（{@code targetsMin: {RESOLUTION: 240}}）；
 * 条件 {@code when} 为 {@code {field, op, value}} 或 {@code {all: [...]}} / {@code {any: [...]}}；起算 / 暂停 / 停止缺省即默认口径。
 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class SlaPolicyController {

    private final SlaPolicyAppService policies;

    public SlaPolicyController(SlaPolicyAppService policies) {
        this.policies = policies;
    }

    public record TriggerDto(Boolean onCreate, List<String> categories, List<String> transitions) {
    }

    public record PauseDto(List<String> categories, List<String> statuses) {
    }

    public record MetricDto(String code, Map<String, String> name, TriggerDto start, PauseDto pause, TriggerDto stop) {
    }

    public record TargetDto(Map<String, Object> when, Map<String, Long> targetsMin, String calendar) {
    }

    public record PolicyDto(String code, Map<String, String> name, List<MetricDto> metrics, List<TargetDto> targets, Integer warnAtPercent,
            String version, Boolean inherited, String note) {
    }

    public record PublishDto(String version, boolean created) {
    }

    @GetMapping("/sla-policies")
    public List<PolicyDto> list(Actor actor) {
        return policies.list(actor.tenant()).stream().map(r -> toDto(r.def(), r.version(), r.inherited())).toList();
    }

    @GetMapping("/sla-policies/{code}")
    public PolicyDto get(Actor actor, @PathVariable("code") String code) {
        return policies.resolve(actor.tenant(), code).map(r -> toDto(r.def(), r.version(), r.inherited()))
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "sla policy not found"));
    }

    @PutMapping("/sla-policies/{code}")
    public PublishDto publish(Actor actor, @PathVariable("code") String code, @RequestBody PolicyDto body) {
        List<FieldError> shape = new ArrayList<>();
        SlaPolicyDef def = fromDto(code, body, shape);
        if (!shape.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid sla policy", Map.of("code", code), shape);
        }
        SlaPolicyAppService.Published p = policies.publish(actor, actor.tenant().workspaceId(), def, body.note(), actor.isAppItself() ? "API" : "CONSOLE");
        return new PublishDto(p.version(), p.created());
    }

    // ── 线上形状 ⇄ 定义 ─────────────────────────────────────

    static SlaPolicyDef fromDto(String code, PolicyDto d, List<FieldError> shape) {
        List<SlaPolicyDef.Metric> metrics = new ArrayList<>();
        List<MetricDto> ms = d.metrics() == null ? List.of() : d.metrics();
        for (int i = 0; i < ms.size(); i++) {
            MetricDto m = ms.get(i);
            String p = "metrics[" + i + "]";
            metrics.add(new SlaPolicyDef.Metric(m.code(), text(m.name()), trigger(m.start(), p + ".start", shape),
                    m.pause() == null ? null : new SlaPolicyDef.Pause(categories(m.pause().categories(), p + ".pause.categories", shape),
                            m.pause().statuses() == null ? Set.of() : Set.copyOf(m.pause().statuses())),
                    trigger(m.stop(), p + ".stop", shape)));
        }
        List<SlaPolicyDef.Target> targets = new ArrayList<>();
        List<TargetDto> ts = d.targets() == null ? List.of() : d.targets();
        for (int i = 0; i < ts.size(); i++) {
            TargetDto t = ts.get(i);
            Map<String, Duration> ds = new TreeMap<>();
            if (t.targetsMin() != null) {
                t.targetsMin().forEach((k, v) -> ds.put(k, v == null ? Duration.ZERO : Duration.ofMinutes(v)));
            }
            targets.add(new SlaPolicyDef.Target(condition(t.when(), "targets[" + i + "].when", shape), ds, t.calendar()));
        }
        return new SlaPolicyDef(code, text(d.name()), metrics, targets, d.warnAtPercent());
    }

    private static SlaPolicyDef.Trigger trigger(TriggerDto t, String path, List<FieldError> shape) {
        if (t == null) {
            return null;
        }
        return new SlaPolicyDef.Trigger(Boolean.TRUE.equals(t.onCreate()), categories(t.categories(), path + ".categories", shape),
                t.transitions() == null ? Set.of() : Set.copyOf(t.transitions()));
    }

    private static Set<StatusCategory> categories(List<String> cs, String path, List<FieldError> shape) {
        if (cs == null) {
            return Set.of();
        }
        try {
            return cs.stream().map(StatusCategory::valueOf).collect(Collectors.toSet());
        } catch (IllegalArgumentException e) {
            shape.add(new FieldError(path, "opskit.validation.one_of"));
            return Set.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Condition condition(Map<String, Object> m, String path, List<FieldError> shape) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        try {
            if (m.containsKey("all") || m.containsKey("any")) {
                List<Condition> items = ((List<Map<String, Object>>) m.getOrDefault("all", m.get("any"))).stream()
                        .map(x -> condition(x, path, shape)).toList();
                return m.containsKey("all") ? new Condition.All(items) : new Condition.Any(items);
            }
            return new Condition.Compare((String) m.get("field"), Op.valueOf(String.valueOf(m.get("op"))), m.get("value"));
        } catch (RuntimeException e) {
            shape.add(new FieldError(path, "opskit.condition.invalid"));
            return null;
        }
    }

    private static LocalizedText text(Map<String, String> m) {
        if (m == null) {
            return null;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank()) {
                kept.put(k, v.strip());
            }
        });
        return kept.isEmpty() ? null : new LocalizedText(kept);
    }

    static PolicyDto toDto(SlaPolicyDef p, String version, boolean inherited) {
        return new PolicyDto(p.code(), p.name() == null ? Map.of() : p.name().values(),
                p.metrics().stream().map(m -> new MetricDto(m.code(), m.name() == null ? Map.of() : m.name().values(), trig(m.start()),
                        m.pause() == null ? null : new PauseDto(m.pause().categories().stream().map(Enum::name).sorted().toList(),
                                m.pause().statuses().stream().sorted().toList()), trig(m.stop()))).toList(),
                p.targets().stream().map(t -> new TargetDto(when(t.when()),
                        new TreeMap<>(t.targets().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toMinutes()))),
                        t.calendar())).toList(),
                p.warnAtPercent(), version, inherited, null);
    }

    private static TriggerDto trig(SlaPolicyDef.Trigger t) {
        return t == null ? null : new TriggerDto(t.onCreate(), t.categories().stream().map(Enum::name).sorted().toList(), t.transitions().stream().sorted().toList());
    }

    private static Map<String, Object> when(Condition c) {
        if (c == null) {
            return null;
        }
        return switch (c) {
            case Condition.Compare x -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", x.field());
                m.put("op", x.op().name());
                if (x.value() != null) {
                    m.put("value", x.value());
                }
                yield m;
            }
            case Condition.All x -> Map.of("all", x.items().stream().map(SlaPolicyController::when).toList());
            case Condition.Any x -> Map.of("any", x.items().stream().map(SlaPolicyController::when).toList());
        };
    }
}
