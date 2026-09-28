package ai.neargo.opskit.ticket.core.domain;

import ai.neargo.opskit.process.api.model.query.SlaMetric;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工单的 SLA 快照（21/04 工单表的 SLA 快照列）：状态取各指标中最差的；到期 / 起算 / 暂停取「到期最近的未停指标」。
 */
public final class SlaRollup {

    /** 越大越差；PENDING / CANCELLED 不参与。 */
    private static final Map<String, Integer> SEVERITY = Map.of("MET", 1, "PAUSED", 2, "RUNNING", 3, "WARNING", 4, "BREACHED", 5);

    public record Rollup(String state, Long nextDueAtMs, Long startedAtMs, long pausedMs) {
        public static final Rollup NONE = new Rollup(null, null, null, 0);
    }

    private SlaRollup() {
    }

    public static Rollup of(List<SlaMetric> metrics) {
        Optional<String> worst = metrics.stream().map(SlaMetric::state).filter(SEVERITY::containsKey).max(Comparator.comparing(SEVERITY::get));
        if (worst.isEmpty()) {
            return Rollup.NONE;
        }
        Optional<SlaMetric> nearest = metrics.stream()
                .filter(m -> m.stoppedAtMs() == null && m.dueAtMs() != null && m.startedAtMs() != null && !"MET".equals(m.state()) && !"CANCELLED".equals(m.state()))
                .min(Comparator.comparing(SlaMetric::dueAtMs));
        return new Rollup(worst.get(), nearest.map(SlaMetric::dueAtMs).orElse(null), nearest.map(SlaMetric::startedAtMs).orElse(null),
                nearest.map(SlaMetric::pausedMs).orElse(0L));
    }
}
