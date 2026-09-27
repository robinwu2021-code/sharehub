package ai.neargo.opskit.process.api.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.process.api.definition.StatusCategory;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 21/07 O2-2：零依赖；可走迁移 · 反查（不唯一报错）· 投影 · 终态推导 · 构建期校验。 */
class StateMachineTest {

    enum S { CREATED, DISPATCHED, PROCESSING, DONE, CLOSED }

    enum E { DISPATCH, ACCEPT, FINISH, CLOSE, WITHDRAW, MERGE }

    /** 模拟一个现有业务的工单状态机：WITHDRAW 与 MERGE 都把未结单关掉（同一对起止两个事件）。 */
    private static TypedStateMachine<S, E> workOrder() {
        return TypedStateMachine.builder("work_order", S.class, E.class)
                .status(S.CREATED, StatusCategory.NEW)
                .status(S.DISPATCHED, StatusCategory.IN_PROGRESS)
                .status(S.PROCESSING, StatusCategory.IN_PROGRESS)
                .status(S.DONE, StatusCategory.DONE)
                .status(S.CLOSED, StatusCategory.CANCELLED)
                .initial(S.CREATED)
                .transition(E.DISPATCH, S.CREATED, S.DISPATCHED)
                .transition(E.ACCEPT, S.DISPATCHED, S.PROCESSING)
                .transition(E.FINISH, S.PROCESSING, S.DONE)
                .transition(E.WITHDRAW, S.CREATED, S.CLOSED)
                .transition(E.WITHDRAW, S.DISPATCHED, S.CLOSED)
                .transition(E.MERGE, S.DISPATCHED, S.CLOSED)
                .build();
    }

    @Test
    void firesLegalTransitionsAndRejectsIllegalOnes() {
        TypedStateMachine<S, E> sm = workOrder();
        assertThat(sm.next(S.CREATED, E.DISPATCH)).isEqualTo(S.DISPATCHED);
        assertThat(sm.canFire(S.CREATED, E.FINISH)).isFalse();
        assertThatThrownBy(() -> sm.next(S.CREATED, E.FINISH))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("FINISH");
        assertThat(sm.events(S.DISPATCHED)).containsExactlyInAnyOrder(E.ACCEPT, E.WITHDRAW, E.MERGE);
    }

    @Test
    void reverseLookupRefusesToGuessWhenAmbiguous() {
        TypedStateMachine<S, E> sm = workOrder();
        assertThat(sm.eventFor(S.CREATED, S.CLOSED)).isEqualTo(E.WITHDRAW);
        assertThatThrownBy(() -> sm.eventFor(S.DISPATCHED, S.CLOSED))
                .isInstanceOf(AmbiguousEventException.class)
                .satisfies(e -> assertThat(((AmbiguousEventException) e).candidates()).containsExactlyInAnyOrder("WITHDRAW", "MERGE"));
        assertThatThrownBy(() -> sm.eventFor(S.CREATED, S.DONE)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void terminalIsDerivedAndProjectionMatchesThePlainGraph() {
        TypedStateMachine<S, E> sm = workOrder();
        assertThat(sm.isTerminal(S.DONE)).isTrue();
        assertThat(sm.isTerminal(S.CLOSED)).isTrue();
        assertThat(sm.isTerminal(S.PROCESSING)).isFalse();
        assertThat(sm.projection().get(S.DISPATCHED)).containsExactlyInAnyOrder(S.PROCESSING, S.CLOSED);
        assertThat(sm.projection().get(S.DONE)).isEmpty();
    }

    @Test
    void brokenDefinitionsFailAtBuildTime() {
        // 不可达状态、终态有出边、没有初始状态 —— 构建即失败并带全部错误
        assertThatThrownBy(() -> TypedStateMachine.builder("bad", S.class, E.class)
                .status(S.CREATED, StatusCategory.NEW).status(S.DONE, StatusCategory.DONE).status(S.PROCESSING, StatusCategory.IN_PROGRESS)
                .initial(S.CREATED)
                .transition(E.FINISH, S.CREATED, S.DONE)
                .transition(E.CLOSE, S.DONE, S.CREATED)
                .build())
                .isInstanceOf(GraphDefinitionException.class)
                .satisfies(e -> assertThat(((GraphDefinitionException) e).errors()).extracting(x -> x.error())
                        .anyMatch(k -> k.endsWith("terminal_has_exit")));
        assertThatThrownBy(() -> TypedStateMachine.builder("noinit", S.class, E.class)
                .status(S.CREATED, StatusCategory.NEW).status(S.DONE, StatusCategory.DONE)
                .transition(E.FINISH, S.CREATED, S.DONE).build())
                .isInstanceOf(GraphDefinitionException.class);
    }

    @Test
    void exportsADefinitionTheServerAccepts() {
        assertThat(workOrder().untyped().definition().transitions()).hasSize(6);
        assertThat(Set.copyOf(workOrder().untyped().definition().statuses().stream().map(s -> s.code()).toList()))
                .containsExactlyInAnyOrder("CREATED", "DISPATCHED", "PROCESSING", "DONE", "CLOSED");
    }
}
