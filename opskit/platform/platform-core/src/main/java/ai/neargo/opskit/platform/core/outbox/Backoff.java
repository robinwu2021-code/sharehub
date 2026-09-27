package ai.neargo.opskit.platform.core.outbox;

import java.time.Duration;
import java.util.List;

/** 投递退避（21/06 §六）：第 n 次失败后等 steps[n-1]；用完即标记失败，等人工重投。 */
public record Backoff(List<Duration> steps) {

    public static final Backoff DEFAULT = new Backoff(List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(12), Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(24)));

    public Backoff {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("backoff needs at least one step");
        }
    }

    public int maxAttempts() {
        return steps.size();
    }

    /** @param attempts 已失败次数（≥ 1） */
    public Duration delayAfter(int attempts) {
        return steps.get(Math.min(attempts, steps.size()) - 1);
    }
}
