package ai.neargo.opskit.process.api.statemachine;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 类型化外观：状态与迁移（事件）都是枚举，两种类型不可混用（需求 SM-01）。
 *
 * <pre>{@code
 * TypedStateMachine<S, E> sm = TypedStateMachine.builder("ticket", S.class, E.class)
 *         .status(S.NEW, StatusCategory.NEW).status(S.DOING, StatusCategory.IN_PROGRESS).status(S.DONE, StatusCategory.DONE)
 *         .initial(S.NEW)
 *         .transition(E.START, S.NEW, S.DOING)
 *         .transition(E.FINISH, S.DOING, S.DONE)
 *         .build();
 * S next = sm.next(S.NEW, E.START);
 * }</pre>
 */
public final class TypedStateMachine<S extends Enum<S>, E extends Enum<E>> {

    private final StateMachine machine;
    private final Class<S> statusType;
    private final Class<E> eventType;

    private TypedStateMachine(StateMachine machine, Class<S> statusType, Class<E> eventType) {
        this.machine = machine;
        this.statusType = statusType;
        this.eventType = eventType;
    }

    public static <S extends Enum<S>, E extends Enum<E>> Builder<S, E> builder(String code, Class<S> statusType, Class<E> eventType) {
        return new Builder<>(code, statusType, eventType);
    }

    public S next(S from, E event) {
        return Enum.valueOf(statusType, machine.next(from.name(), event.name()));
    }

    public boolean canFire(S from, E event) {
        return machine.canFire(from.name(), event.name());
    }

    public List<E> events(S from) {
        return machine.transitionsFrom(from.name()).stream().map(n -> Enum.valueOf(eventType, n)).toList();
    }

    public E eventFor(S from, S to) {
        return Enum.valueOf(eventType, machine.transitionFor(from.name(), to.name()));
    }

    public boolean isTerminal(S status) {
        return machine.isTerminal(status.name());
    }

    public Map<S, Set<S>> projection() {
        Map<S, Set<S>> out = new EnumMap<>(statusType);
        machine.projection().forEach((k, v) -> out.put(Enum.valueOf(statusType, k),
                v.stream().map(x -> Enum.valueOf(statusType, x)).collect(Collectors.toUnmodifiableSet())));
        return out;
    }

    /** 底层字符串版（导出定义、登记到服务用）。 */
    public StateMachine untyped() {
        return machine;
    }

    public static final class Builder<S extends Enum<S>, E extends Enum<E>> {
        private final String code;
        private final Class<S> statusType;
        private final Class<E> eventType;
        private final List<StatusDef> statuses = new ArrayList<>();
        private final List<TransitionDef> transitions = new ArrayList<>();
        private S initial;

        private Builder(String code, Class<S> statusType, Class<E> eventType) {
            this.code = code;
            this.statusType = statusType;
            this.eventType = eventType;
        }

        public Builder<S, E> status(S status, StatusCategory category) {
            statuses.add(new StatusDef(status.name(), LocalizedText.of("en", status.name()), category, null));
            return this;
        }

        public Builder<S, E> initial(S status) {
            this.initial = status;
            return this;
        }

        /**
         * 一条迁移：{@code event} 从 {@code from} 到 {@code to}。同一事件从多个状态出发就多调用几次；
         * 同一对起止允许多个事件（需求 SM-02）。参数顺序刻意与读法一致（事件、从、到），不用可变参数把「到」夹在中间。
         */
        public Builder<S, E> transition(E event, S from, S to) {
            transitions.add(new TransitionDef(event.name(), LocalizedText.of("en", event.name()), Set.of(from.name()), to.name(),
                    null, null, null, null, false));
            return this;
        }

        /** 构建期校验失败抛 {@link GraphDefinitionException}（需求 SM-08）。 */
        public TypedStateMachine<S, E> build() {
            ProcessDef def = new ProcessDef(code, LocalizedText.of("en", code), initial == null ? null : initial.name(),
                    statuses, transitions, Set.of());
            return new TypedStateMachine<>(StateMachine.of(def), statusType, eventType);
        }
    }
}
