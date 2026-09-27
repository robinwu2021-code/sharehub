package ai.neargo.opskit.commons.condition;

import java.util.List;
import java.util.Objects;

/**
 * 条件：字段比较，或一层 all / any 组合。组合里还能再嵌一层组合，但不能更深（{@link #MAX_DEPTH}）。
 * 更复杂的判断由调用方预先算成一个字段传进来。
 */
public sealed interface Condition {

    int MAX_DEPTH = 2;

    record Compare(String field, Op op, Object value) implements Condition {
        public Compare {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("condition field must not be blank");
            }
            Objects.requireNonNull(op, "op");
            if (op.needsValue() && value == null) {
                throw new IllegalArgumentException(op + " needs a value");
            }
            if (op == Op.IN && !(value instanceof java.util.Collection<?>)) {
                throw new IllegalArgumentException("IN needs a collection value");
            }
        }
    }

    record All(List<Condition> items) implements Condition {
        public All {
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("all() needs at least one item");
            }
        }
    }

    record Any(List<Condition> items) implements Condition {
        public Any {
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("any() needs at least one item");
            }
        }
    }

    /** 叶子深度为 0；all(a, b) 为 1；all(a, any(b, c)) 为 2。 */
    default int depth() {
        return switch (this) {
            case Compare c -> 0;
            case All a -> 1 + a.items().stream().mapToInt(Condition::depth).max().orElse(0);
            case Any a -> 1 + a.items().stream().mapToInt(Condition::depth).max().orElse(0);
        };
    }
}
