package ai.neargo.sharehub.audit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 本次请求改了哪些字段、从什么变成什么 —— 审计详情里那张「改前改后」表的数据来源。
 *
 * <h2>为什么需要它：action 回答不了的那个问题</h2>
 * 审计现在记的是 {@code POST /api/trade/share-rules}。看到这一行只知道
 * <b>有人动过这条规则</b>，不知道动了什么。而结算争议里要问的恰恰是
 * 「费率是从 8% 改成 5% 的，还是一直就是 5%」—— 现在答不出来，
 * 只能去翻数据库的当前值，而当前值正是被改过之后的那个。
 *
 * <p>读侧早就在解析 {@code {"changes":[{"field","before","after"}]}}
 * （{@code AuditLogServiceImpl.changesOf}），只是<b>从来没有人写</b>，
 * 于是详情页那张表永远是空的。本类补的是写侧。
 *
 * <h2>为什么是「服务显式记」而不是注解自动 diff</h2>
 * 自动 diff 要解决两件事，而两件都解决不好：
 * <ul>
 *   <li><b>「改前」从哪来</b> —— 没有通用的「按 id 把旧实体查出来」，
 *       每个资源的主键、mapper、软删语义都不一样；</li>
 *   <li><b>diff 什么</b> —— 全量 diff 会把密码散列、令牌、几 KB 的 JSON 列
 *       一起卷进 {@code detail}。方案自己也把「快照撑爆 detail」列为风险，
 *       对策是<b>按资源声明字段白名单</b>，而白名单和「自动」本来就是矛盾的。</li>
 * </ul>
 * 显式记反而两件都天然解决：更新路径本来就查了旧实体（不查没法更新），
 * 而记哪几个字段是调用处写死的 —— <b>白名单即调用</b>。
 * 代价是要逐个资源接入，这正是方案里「增量启用」的意思。
 *
 * <h2>三条硬约束</h2>
 * <ol>
 *   <li><b>值没变就不记</b>。一次保存二十个字段只改了一个，却列出二十条
 *       「X → X」，等于把唯一有用的那条藏进噪音里；</li>
 *   <li><b>有上限</b>（{@link #MAX_CHANGES} 条 · 每个值 {@link #MAX_VALUE_LEN} 字）。
 *       超了截断并标 {@code …} —— 截断必须看得出来，
 *       否则有人会拿一个被截断的值当原值去对账；</li>
 *   <li><b>请求结束必须清</b>。线程池复用下不清，下一个请求的审计里会带上
 *       <b>上一个请求改了什么</b> —— 那不是少了信息，是记了假的。</li>
 * </ol>
 */
public final class AuditChanges {

    /** 一条字段改动。{@code before}/{@code after} 已经是给人看的字符串。 */
    public record Change(String field, String before, String after) {
    }

    /** 单次请求最多记多少条。够看清「改了什么」，又不至于把 detail 撑成一篇文章。 */
    public static final int MAX_CHANGES = 20;

    /** 单个值最多多少字，超出截断并标 {@code …}。 */
    public static final int MAX_VALUE_LEN = 200;

    /** 空值的显示形态 —— 与读侧 {@code AuditLogServiceImpl.DASH} 一致。 */
    public static final String EMPTY = "—";

    private static final ThreadLocal<List<Change>> CURRENT = new ThreadLocal<>();

    private AuditChanges() {
    }

    /**
     * 记一条改动。<b>两值相等则什么都不做</b>（理由见类注释第 1 条）。
     *
     * <p>值由调用方挑，所以这里不需要脱敏规则 —— <b>不该进审计的字段压根别传进来</b>。
     * 反过来说：传了就会被记下，手机号、密钥、银行账号请先自行脱敏。
     */
    public static void record(String field, Object before, Object after) {
        if (field == null || field.isBlank()) return;
        // **比较的是人看到的那个值**，不是对象。BigDecimal 的 equals 认标度：
        // 库里取出来的 0.0800 与请求体里的 0.08 不相等，于是「原样重存一次」
        // 会被记成一条「0.0800 → 0.08」的改动 —— 一条什么都没改的改动，
        // 正是第 1 条要避免的噪音。渲染出来一样就是没变。
        String b = display(before);
        String a = display(after);
        if (b.equals(a)) return;
        List<Change> list = CURRENT.get();
        if (list == null) {
            list = new ArrayList<>();
            CURRENT.set(list);
        }
        if (list.size() >= MAX_CHANGES) return;
        list.add(new Change(field, b, a));
    }

    /**
     * 比较两个同类型对象的**指定字段**（白名单），把变了的记下来。
     *
     * <p>字段名按声明顺序找，含父类。找不到的字段<b>直接忽略而不是抛异常</b> ——
     * 审计是旁路，为了记一条留痕让业务保存失败是本末倒置；
     * 写错字段名的代价是那一列不出现在详情里，会在接入时的用例里暴露。
     */
    public static void compare(Object before, Object after, String... fields) {
        if (before == null || after == null || fields == null) return;
        for (String f : fields) {
            Field fd = findField(after.getClass(), f);
            if (fd == null) continue;
            try {
                fd.setAccessible(true);
                record(f, fd.get(before), fd.get(after));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 取不到就不记这一列。理由同上：审计不该让业务失败
            }
        }
    }

    /** 取出并清空 —— 由审计拦截器在写记录时调用。 */
    public static List<Change> drain() {
        List<Change> list = CURRENT.get();
        CURRENT.remove();
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 请求结束兜底清理（{@link #drain()} 已经清过，这里是没走到 drain 的路径用的）。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 给人看的形态。
     *
     * <p>{@code BigDecimal} 去掉尾随零：库里是 {@code DECIMAL(x,4)}，取出来的
     * {@code 0.0800} 与请求体里的 {@code 0.08} 是同一个数。
     * 原样显示的话，一条「0.0800 → 0.05」会让人以为连标度都被改了 ——
     * <b>diff 该显示值的变化，不是表示法的变化</b>。
     */
    private static String display(Object v) {
        if (v == null) return EMPTY;
        if (v instanceof java.math.BigDecimal d) {
            String p = d.stripTrailingZeros().toPlainString();
            return p.isEmpty() ? EMPTY : p;
        }
        String s = String.valueOf(v);
        if (s.isEmpty()) return EMPTY;
        // 截断要看得出来：不标的话，有人会拿被截断的值当原值去对账
        return s.length() <= MAX_VALUE_LEN ? s : s.substring(0, MAX_VALUE_LEN) + "…";
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }
}
