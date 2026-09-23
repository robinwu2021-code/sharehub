package ai.neargo.sharehub.trade.price.entity;

import java.util.Arrays;
import java.util.Optional;

/**
 * 适用范围的层（ADR-028 §二）。**顺序即优先级**：序号小的更具体、更优先。
 *
 * <p>把优先级做成枚举的声明顺序而不是散在代码里的 if/else，是本次重写的要点：
 * 取价规则从此是**数据 + 一个排序**，加一层只需在这里插一行并在
 * {@code PriceQuery} 里给出它的引用值。
 *
 * <h3>为什么 VENUE 在 AGENT 之上</h3>
 * 场地方的价格约束来自**进场合同**，是对外承诺；伙伴定价是内部安排。
 * 机场说「全场 5 块」时，伙伴不能在机场里自己定 8 块。
 *
 * <h3>为什么 DEVICE 在最上、LOCATION 在 SITE 之上</h3>
 * 粒度越细越是**有意为之的例外**，例外优先于常规。
 */
public enum ScopeLevel {

    /** 单台特价（一台快充柜比同站其它柜贵）。 */
    DEVICE,
    /** 大商场 / 机场分区：贵宾厅一个价、美食广场一个价，不必为此拆站点。 */
    LOCATION,
    /** 站点专属。 */
    SITE,
    /** 场地方统一价（通常写在进场合同里）。 */
    VENUE,
    /** 伙伴级价：一个合作伙伴名下所有站点一个价。 */
    AGENT,
    /** 按场景（商场 / 医院 / 机场）。 */
    SCENE,
    /** 按区域（城市级定价）。 */
    REGION,
    /** 该设备类型的默认方案。{@code scopeRef} 固定为 {@link #ALL_REF}。 */
    ALL;

    /** {@code ALL} 层的引用值。空串会与「没填」混淆，故用显式的 {@code *}。 */
    public static final String ALL_REF = "*";

    /** 宽松解析：不认识的值返回空，由调用方决定是忽略还是报错（存量脏数据不该让取价整体失败）。 */
    public static Optional<ScopeLevel> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }
}
