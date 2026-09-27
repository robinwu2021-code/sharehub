package ai.neargo.opskit.commons.condition;

/** 条件操作符。刻意只有比较，没有表达式（01 PRC-04 / 02 D9）。 */
public enum Op {
    EQ, NE, IN, GT, GTE, LT, LTE, EXISTS, EMPTY;

    /** EXISTS / EMPTY 不需要比较值。 */
    public boolean needsValue() {
        return this != EXISTS && this != EMPTY;
    }
}
