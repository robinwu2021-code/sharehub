package ai.neargo.opskit.process.core.domain.definition;

import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.process.api.definition.sla.SlaPolicyDef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** SLA 策略的结构校验（21/02 §6.5），错误路径按策略字段给（{@code targets[1].targets.RESOLUTION}）。 */
public final class SlaPolicyValidator {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern METRIC = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Duration MAX = Duration.ofDays(366);

    private SlaPolicyValidator() {
    }

    public static List<FieldError> validate(SlaPolicyDef p) {
        List<FieldError> e = new ArrayList<>();
        if (p.code() == null || !CODE.matcher(p.code()).matches()) {
            e.add(new FieldError("code", "opskit.validation.code_format"));
        }
        if (p.metrics().isEmpty()) {
            e.add(new FieldError("metrics", "opskit.validation.required"));
        }
        Set<String> metrics = new HashSet<>();
        for (int i = 0; i < p.metrics().size(); i++) {
            SlaPolicyDef.Metric m = p.metrics().get(i);
            String path = "metrics[" + i + "]";
            if (m.code() == null || !METRIC.matcher(m.code()).matches()) {
                e.add(new FieldError(path + ".code", "opskit.sla.metric_code_format"));
            } else if (!metrics.add(m.code())) {
                e.add(new FieldError(path + ".code", "opskit.validation.duplicate"));
            }
            if (m.stop() != null && m.stop().onCreate()) {
                e.add(new FieldError(path + ".stop.onCreate", "opskit.sla.stop_on_create"));
            }
            SlaPolicyDef.Trigger start = m.startOrDefault();
            if (!start.onCreate() && start.categories().isEmpty() && start.transitions().isEmpty()) {
                e.add(new FieldError(path + ".start", "opskit.sla.never_starts"));
            }
            SlaPolicyDef.Trigger stop = m.stopOrDefault();
            if (stop.categories().isEmpty() && stop.transitions().isEmpty()) {
                e.add(new FieldError(path + ".stop", "opskit.sla.never_stops"));
            }
        }
        if (p.targets().isEmpty()) {
            e.add(new FieldError("targets", "opskit.validation.required"));
        }
        for (int i = 0; i < p.targets().size(); i++) {
            SlaPolicyDef.Target t = p.targets().get(i);
            if (t.targets().isEmpty()) {
                e.add(new FieldError("targets[" + i + "].targets", "opskit.validation.required"));
            }
            int row = i;
            t.targets().keySet().stream().sorted().forEach(metric -> {
                Duration d = t.targets().get(metric);
                String path = "targets[" + row + "].targets." + metric;
                if (!metrics.contains(metric)) {
                    e.add(new FieldError(path, "opskit.sla.unknown_metric"));
                } else if (d == null || d.isZero() || d.isNegative() || d.compareTo(MAX) > 0) {
                    e.add(new FieldError(path, "opskit.validation.out_of_range"));
                }
            });
            if (t.when() == null && i < p.targets().size() - 1) {
                // 自上而下第一条匹配：兜底行后面的行永远轮不到
                e.add(new FieldError("targets[" + (i + 1) + "]", "opskit.sla.unreachable_target"));
            }
        }
        if (p.warnAtPercent() != null && (p.warnAtPercent() < 1 || p.warnAtPercent() > 99)) {
            e.add(new FieldError("warnAtPercent", "opskit.validation.out_of_range"));
        }
        return e;
    }
}
