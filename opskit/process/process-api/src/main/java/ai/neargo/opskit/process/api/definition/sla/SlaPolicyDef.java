package ai.neargo.opskit.process.api.definition.sla;

import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SLA 策略（21/02 §6.5）：若干指标（响应、解决……）+ 目标矩阵（自上而下第一条匹配）+ 预警比例。
 * 工单类型或调用方在启动实例时引用一个或多个策略；每个（实例, 策略, 指标）一只计时器。
 *
 * @param warnAtPercent 用去目标时长的百分之几时预警（1–99）；空 = 不预警
 */
public record SlaPolicyDef(String code, LocalizedText name, List<Metric> metrics, List<Target> targets, Integer warnAtPercent) {

    public SlaPolicyDef {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    /**
     * @param start 起算：空 = 实例创建即起算
     * @param pause 暂停：空 = 处于 WAITING 类别时暂停
     * @param stop  停止：空 = 进入 DONE / CANCELLED 类别
     */
    public record Metric(String code, LocalizedText name, Trigger start, Pause pause, Trigger stop) {

        public Trigger startOrDefault() {
            return start == null ? Trigger.ON_CREATE : start;
        }

        public Pause pauseOrDefault() {
            return pause == null ? Pause.WAITING : pause;
        }

        public Trigger stopOrDefault() {
            return stop == null ? Trigger.CLOSED : stop;
        }
    }

    /** 任一命中即触发：实例创建（仅 start 有意义）· 进入某些类别 · 走了某些迁移。 */
    public record Trigger(boolean onCreate, Set<StatusCategory> categories, Set<String> transitions) {
        public static final Trigger ON_CREATE = new Trigger(true, Set.of(), Set.of());
        public static final Trigger CLOSED = new Trigger(false, Set.of(StatusCategory.DONE, StatusCategory.CANCELLED), Set.of());

        public Trigger {
            categories = categories == null ? Set.of() : Set.copyOf(categories);
            transitions = transitions == null ? Set.of() : Set.copyOf(transitions);
        }
    }

    /** 处于这些类别或状态时不计时。 */
    public record Pause(Set<StatusCategory> categories, Set<String> statuses) {
        public static final Pause WAITING = new Pause(Set.of(StatusCategory.WAITING), Set.of());

        public Pause {
            categories = categories == null ? Set.of() : Set.copyOf(categories);
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        }
    }

    /**
     * 目标矩阵的一行。
     *
     * @param when     空 = 兜底行（总是匹配）
     * @param targets  指标代号 → 目标时长；某指标不在这一行 = 该情形下不考核它
     * @param calendar 工作日历代号；空 = 7×24 自然时间
     */
    public record Target(Condition when, Map<String, Duration> targets, String calendar) {
        public Target {
            targets = targets == null ? Map.of() : Map.copyOf(targets);
        }
    }
}
