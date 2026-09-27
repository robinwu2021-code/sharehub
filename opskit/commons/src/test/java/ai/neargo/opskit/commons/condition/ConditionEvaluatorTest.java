package ai.neargo.opskit.commons.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

    private static Condition cmp(String f, Op op, Object v) {
        return new Condition.Compare(f, op, v);
    }

    @Test
    void numbersCompareByValueNotByType() {
        Map<String, Object> f = Map.of("amount", 1200, "rate", new BigDecimal("0.35"));
        assertThat(ConditionEvaluator.evaluate(cmp("amount", Op.EQ, 1200.0), f)).isTrue();
        assertThat(ConditionEvaluator.evaluate(cmp("amount", Op.GTE, 1000L), f)).isTrue();
        assertThat(ConditionEvaluator.evaluate(cmp("rate", Op.LT, 0.4), f)).isTrue();
    }

    @Test
    void orderingOnIncomparableValuesIsFalseForEveryOperator() {
        // 负对照：曾经用 0 充当「不可比较」，会让 GTE / LTE 在类型不同时判真
        Map<String, Object> f = Map.of("amount", "not-a-number");
        for (Op op : List.of(Op.GT, Op.GTE, Op.LT, Op.LTE)) {
            assertThat(ConditionEvaluator.evaluate(cmp("amount", op, 10), f)).as(op.name()).isFalse();
        }
        assertThat(ConditionEvaluator.evaluate(cmp("missing", Op.GTE, 10), f)).isFalse();
    }

    @Test
    void mismatchedTypesAreNotEqual() {
        Map<String, Object> f = Map.of("flag", "true");
        assertThat(ConditionEvaluator.evaluate(cmp("flag", Op.EQ, true), f)).isFalse();
        assertThat(ConditionEvaluator.evaluate(cmp("flag", Op.NE, true), f)).isTrue();
    }

    @Test
    void multiSelectFieldEqualsWhenItContainsTheValue() {
        Map<String, Object> f = Map.of("tags", List.of("vip", "night"));
        assertThat(ConditionEvaluator.evaluate(cmp("tags", Op.EQ, "vip"), f)).isTrue();
        assertThat(ConditionEvaluator.evaluate(cmp("tags", Op.IN, List.of("x", "night")), f)).isTrue();
        assertThat(ConditionEvaluator.evaluate(cmp("tags", Op.EQ, "day"), f)).isFalse();
    }

    @Test
    void existsAndEmpty() {
        Map<String, Object> f = Map.of("a", "", "b", List.of(), "c", "x");
        assertThat(ConditionEvaluator.evaluate(new Condition.Compare("a", Op.EMPTY, null), f)).isTrue();
        assertThat(ConditionEvaluator.evaluate(new Condition.Compare("b", Op.EMPTY, null), f)).isTrue();
        assertThat(ConditionEvaluator.evaluate(new Condition.Compare("missing", Op.EMPTY, null), f)).isTrue();
        assertThat(ConditionEvaluator.evaluate(new Condition.Compare("c", Op.EXISTS, null), f)).isTrue();
    }

    @Test
    void twoLevelsOfAllAnyAreSupported() {
        Condition c = new Condition.All(List.of(
                cmp("priority", Op.EQ, "HIGH"),
                new Condition.Any(List.of(cmp("type", Op.EQ, "repair"), cmp("amount", Op.GT, 1000)))));
        assertThat(c.depth()).isEqualTo(2);
        assertThat(ConditionEvaluator.evaluate(c, Map.of("priority", "HIGH", "type", "x", "amount", 1500))).isTrue();
        assertThat(ConditionEvaluator.evaluate(c, Map.of("priority", "HIGH", "type", "x", "amount", 500))).isFalse();
    }

    @Test
    void deeperThanTwoLevelsIsRejected() {
        Condition deep = new Condition.All(List.of(new Condition.Any(List.of(new Condition.All(List.of(cmp("a", Op.EQ, 1)))))));
        assertThat(deep.depth()).isEqualTo(3);
        assertThatThrownBy(() -> ConditionEvaluator.evaluate(deep, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inNeedsACollectionAndComparisonsNeedAValue() {
        assertThatThrownBy(() -> cmp("a", Op.IN, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cmp("a", Op.EQ, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
