package ai.neargo.sharehub.platform.org.service;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 数据范围档位（{@code iam_data_scope.scope_type}）。
 *
 * <h3>「声明」与「实现」是两件事</h3>
 * 本枚举是<b>后端接受的取值</b>。某个档位能不能真的过滤出数据，取决于
 * {@code DataScopeRegistration} 里有没有表为它登记锚点列 ——
 * 而 {@code DataScopeHandler} 是 <b>fail-closed</b> 的：登记不到就生成 {@code 1=0}。
 *
 * <p>所以 {@link #LOCATION} 与 {@link #VENUE} <b>今天一张表都没登记</b>：
 * 接受它们只是为了不破坏历史数据，<b>运营端不该把它们放进下拉</b> ——
 * 选了就什么都看不见，而且不报错。{@code DataScopeOptionsTest} 盯住这条。
 */
public enum DataScopeType {

    /** 不加任何过滤。唯一一个「不需要任何表登记也正确」的档位。 */
    ALL,
    REGION,
    SITE,
    /** ⚠️ 未登记任何表 —— 见类注释。 */
    LOCATION,
    /** ⚠️ 未登记任何表 —— 见类注释。 */
    VENUE,
    AGENT,
    /** 仅自己经手。范围值是本人的号，由 {@code PermissionService} 在取用时补上。 */
    SELF;

    /** 语义上不带范围值的档位：落库前清空 refs，避免残留旧值造成「看似有范围」的误解。 */
    public static final Set<DataScopeType> WITHOUT_REFS = Set.of(ALL, SELF);

    public static Optional<DataScopeType> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
