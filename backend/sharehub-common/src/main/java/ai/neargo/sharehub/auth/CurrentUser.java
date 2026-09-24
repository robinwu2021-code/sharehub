package ai.neargo.sharehub.auth;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 当前登录身份的线程持有者 —— **纯 ThreadLocal，零 Spring 依赖**。
 *
 * <h2>为什么不直接用 SecurityContextHolder</h2>
 * {@code SecurityContextHolder} 能用的前提是「这是一个被 Spring Security 过滤链处理过的
 * web 请求线程」。而本项目里有大量代码不满足这个前提：
 * <ul>
 *   <li><b>定时任务</b> —— 预约调价执行器、outbox 投递器、巡检产单，都不是 web 线程；</li>
 *   <li><b>异步与线程池</b> —— {@code SecurityContextHolder} 默认策略是 ThreadLocal，
 *       <b>不会传到子线程</b>。{@code @Async} 里读到的是空；</li>
 *   <li><b>MyBatis 拦截器 / 审计填充</b> —— 在更靠下的层里想知道「这是谁改的」。</li>
 * </ul>
 * 这些地方要么拿不到身份，要么被迫把 {@code operator} 一路当参数传下去 ——
 * 后者是本仓已经出现过的形状（{@code NotifyController#currentOperator()} 往下传）。
 *
 * <h2>三条规矩</h2>
 * <ol>
 *   <li><b>谁设置谁清理，且必须 finally。</b> 线程池会复用线程 ——
 *       不清理的话，上一个请求的身份会被下一个请求读到。
 *       <b>那不是「读到空」，是「读到别人」</b>，比没有身份危险得多。
 *       B7 的 {@code TraceContext} 栽过同一个坑，那里的处置可以照抄。</li>
 *   <li><b>不要用 {@code InheritableThreadLocal} 做自动传播。</b>
 *       线程池里的线程「父线程」是创建池的那个（通常是启动线程），
 *       继承过来的是个陈旧身份，且**看起来像是对的**。
 *       跨线程要显式 {@link #callWith} / {@link #runWith}。</li>
 *   <li><b>系统操作要有明确主体</b>（{@link #system}），不要留 null。
 *       审计里记 {@code SYSTEM:outbox-dispatcher} 比记空值有用得多。</li>
 * </ol>
 */
public final class CurrentUser {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    /** 当前身份；无则 empty。**不抛异常** —— 「没登录」在很多地方是合法状态。 */
    public static Optional<LoginUser> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    /** 由各端认证过滤器调用。业务代码不该调它。 */
    public static void set(LoginUser user) {
        if (user == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(user);
        }
    }

    /** **必须在 finally 里调**，见类注释规矩一。 */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 系统身份 —— 定时任务、事件消费、数据回填用。
     *
     * @param who 做这件事的组件，会出现在审计里（如 {@code outbox-dispatcher}）
     */
    public static LoginUser system(String who) {
        return new LoginUser(Realm.STAFF, "SYSTEM:" + who, "系统", "SYSTEM",
                List.of("*"), "MAIN", null, null);
    }

    /**
     * 以指定身份跑一段有返回值的代码，结束后**恢复原身份**而不是清空。
     *
     * <p>恢复而非清空是刻意的：嵌套调用时清空会让外层的身份凭空消失。
     */
    public static <T> T callWith(LoginUser user, Callable<T> task) throws Exception {
        LoginUser prev = HOLDER.get();
        set(user);
        try {
            return task.call();
        } finally {
            set(prev);
        }
    }

    /** {@link #callWith} 的无返回值版本。 */
    public static void runWith(LoginUser user, Runnable task) {
        LoginUser prev = HOLDER.get();
        set(user);
        try {
            task.run();
        } finally {
            set(prev);
        }
    }

    /**
     * 抓一份当前身份，交给别的线程用。
     *
     * <p>配合 {@link #runWith} 显式传播：
     * <pre>{@code
     * LoginUser snapshot = CurrentUser.capture();
     * executor.submit(() -> CurrentUser.runWith(snapshot, this::doWork));
     * }</pre>
     * 写成两步而不是包一个「自动传播的线程池」，是因为**传播与否应当在调用点可见** ——
     * 自动传播会让人以为异步任务里的身份是它自己的。
     */
    public static LoginUser capture() {
        return HOLDER.get();
    }
}
