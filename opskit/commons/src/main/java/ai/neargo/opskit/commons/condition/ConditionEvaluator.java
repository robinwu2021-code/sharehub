package ai.neargo.opskit.commons.condition;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 条件求值。字段值只接受标量（字符串 · 数字 · 布尔）与标量集合，与定义校验时的约束一致。
 *
 * <p>比较规则：数字一律按 {@link BigDecimal} 比（避免 1 与 1.0 不相等）；字符串按字典序；
 * 类型不同的两个值 EQ 为假、NE 为真、大小比较为假 —— 条件不成立而不是抛异常，
 * 因为租户的字段值可能缺失或类型漂移，迁移应表现为「条件不满足」。
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    public static boolean evaluate(Condition condition, Map<String, ?> fields) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(fields, "fields");
        if (condition.depth() > Condition.MAX_DEPTH) {
            throw new IllegalArgumentException("condition deeper than " + Condition.MAX_DEPTH);
        }
        return eval(condition, fields);
    }

    private static boolean eval(Condition condition, Map<String, ?> fields) {
        return switch (condition) {
            case Condition.All all -> all.items().stream().allMatch(c -> eval(c, fields));
            case Condition.Any any -> any.items().stream().anyMatch(c -> eval(c, fields));
            case Condition.Compare cmp -> compare(cmp, fields.get(cmp.field()));
        };
    }

    private static boolean compare(Condition.Compare cmp, Object actual) {
        return switch (cmp.op()) {
            case EXISTS -> !isEmpty(actual);
            case EMPTY -> isEmpty(actual);
            case EQ -> same(actual, cmp.value());
            case NE -> !same(actual, cmp.value());
            case IN -> ((Collection<?>) cmp.value()).stream().anyMatch(v -> same(actual, v));
            case GT -> order(actual, cmp.value()).map(o -> o > 0).orElse(false);
            case GTE -> order(actual, cmp.value()).map(o -> o >= 0).orElse(false);
            case LT -> order(actual, cmp.value()).map(o -> o < 0).orElse(false);
            case LTE -> order(actual, cmp.value()).map(o -> o <= 0).orElse(false);
        };
    }

    private static boolean isEmpty(Object v) {
        return v == null
                || (v instanceof String s && s.isEmpty())
                || (v instanceof Collection<?> c && c.isEmpty());
    }

    /** 集合字段做 EQ 时，含该值即算相等（多选字段 = 某选项）。 */
    private static boolean same(Object actual, Object expected) {
        if (actual instanceof Collection<?> c) {
            return c.stream().anyMatch(item -> scalarEquals(item, expected));
        }
        return scalarEquals(actual, expected);
    }

    private static boolean scalarEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return toDecimal(x).compareTo(toDecimal(y)) == 0;
        }
        return a.getClass() == b.getClass() && a.equals(b);
    }

    /** 不可比较（类型不同、非数字非字符串、缺值）时为空，调用方据此判「条件不成立」。 */
    private static Optional<Integer> order(Object actual, Object expected) {
        if (actual instanceof Number x && expected instanceof Number y) {
            return Optional.of(toDecimal(x).compareTo(toDecimal(y)));
        }
        if (actual instanceof String x && expected instanceof String y) {
            return Optional.of(x.compareTo(y));
        }
        return Optional.empty();
    }

    private static BigDecimal toDecimal(Number n) {
        return n instanceof BigDecimal d ? d : new BigDecimal(n.toString());
    }
}
