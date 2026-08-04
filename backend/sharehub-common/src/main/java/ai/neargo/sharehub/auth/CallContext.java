package ai.neargo.sharehub.auth;

/**
 * 当前调用的**凭证载体**，供跨服务调用透传。
 *
 * <h3>⚠️ 透传的是 token，不是身份声明</h3>
 * 本项目的红线是「**只认 token 反查权限，绝不信客户端 X-Roles/X-User-Id**」
 * （见 {@link StaffTokenAuthFilter}）。跨服务调用若改传 `X-User-Id`/`X-Roles`，
 * 等于让**能访问 `/internal/**` 的任何人冒充任意身份** —— 红线当场作废。
 *
 * <p>所以这里只捎带原始 token：对端用自己的 {@link TokenStore} 反查，
 * 得到同一个 {@code LoginUser} 与 {@code DataScopeSpec}。
 * <b>权限判定始终发生在持有 TokenStore 的那一侧</b>，与单体形态完全一致。
 *
 * <h3>为什么不是从 SecurityContext 取</h3>
 * {@code SecurityContext} 里是解析后的 {@code LoginUser}，**原始 token 已经不在了**。
 * 要透传就必须在过滤器里另存一份。
 *
 * <h3>ThreadLocal 的边界</h3>
 * 异步线程（{@code @Async}、事件监听、定时任务）**拿不到**这个值 ——
 * 那是有意的：后台任务没有「调用方身份」，它们应当显式声明以什么身份运行，
 * 而不是悄悄继承某个恰好触发它的用户。
 */
public final class CallContext {

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private CallContext() {
    }

    /** 由认证过滤器写入。 */
    public static void setToken(String token) {
        TOKEN.set(token);
    }

    /** 供 {@code RemoteCaller} 读取；无调用方上下文时返回 null。 */
    public static String token() {
        return TOKEN.get();
    }

    /** 请求结束必须清理 —— 线程池复用下不清会把上一个请求的身份泄露给下一个。 */
    public static void clear() {
        TOKEN.remove();
    }
}
