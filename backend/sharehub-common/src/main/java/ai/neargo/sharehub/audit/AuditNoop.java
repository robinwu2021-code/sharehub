package ai.neargo.sharehub.audit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 「这次请求什么也没做」—— 端点用它把空转挡在审计外面。
 *
 * <h2>为什么需要它：写方法不等于操作</h2>
 * 审计拦截器拿 HTTP 方法近似「有没有人做了一件事」。这个近似对人触发的端点成立，
 * 对<b>定时端点</b>不成立：调价 tick 挂在 {@code * * * * *} 的 cron 上
 * （{@code deploy/tencent/cron/powerbank-price-adjustment}），
 * 九成九的执行什么也没做，却每次都是一个 POST。
 *
 * <p>不挡的话是<b>一天 1440 行、一个月 4.3 万行</b>，而真实的运营写操作一天只有几百条。
 * 那些行还什么都不说 —— 没有对象、没有改动。于是查「谁改了分润规则」时，
 * 真正那一行躺在几万行同样的噪音里：<b>审计表变大不等于覆盖变好</b>，
 * 大到没人愿意翻的时候，它等于不存在。
 *
 * <h2>为什么不整条豁免那个端点</h2>
 * 因为<b>干了活的那一次是要留痕的</b>：它是审计表里唯一能看到「系统自己改了价格」的地方，
 * 而 traceId 会把它接到运行日志里那几行 {@code 调价 X 已生效：方案 Y {…} → {…}}。
 * 整条豁免会连那一次一起丢掉 —— 省噪音的正确做法是<b>只省掉没有内容的那些</b>，
 * 不是把这一类都关掉。
 *
 * <h2>为什么用请求属性而不是 ThreadLocal</h2>
 * 请求属性<b>随请求一起消失</b>，不需要任何清理纪律。
 * {@link AuditChanges} 那种 ThreadLocal 的代价在它的类注释里写着：
 * 忘了清，下一个请求的审计里会带上上一个请求的内容 —— 那不是少了信息，是记了假的。
 * 这里没有那个风险，就不要引入它。
 *
 * <h2>用它的边界</h2>
 * <b>只用于「这次确实什么都没发生」</b>，不是「这次不重要」「这次不想被看见」。
 * 判断依据必须是端点自己算出来的事实（比如 {@code touched.isEmpty()}），
 * 不能是路径、角色或调用方。用错的后果是<b>静默的</b>：事后查「谁做的」，
 * 答案会是「没有人」。所以调用点数量有卡口看着
 * （{@code AuditNotAnOperationTest}）。
 */
public final class AuditNoop {

    /** 请求属性名。读侧是审计拦截器。 */
    public static final String ATTRIBUTE = "sharehub.audit.noop";

    private AuditNoop() {
    }

    /** 声明本次请求什么也没做 —— 审计将跳过它。 */
    public static void mark(HttpServletRequest req) {
        if (req != null) req.setAttribute(ATTRIBUTE, Boolean.TRUE);
    }

    /** 本次请求是否已被声明为空转。 */
    public static boolean marked(HttpServletRequest req) {
        return req != null && Boolean.TRUE.equals(req.getAttribute(ATTRIBUTE));
    }
}
