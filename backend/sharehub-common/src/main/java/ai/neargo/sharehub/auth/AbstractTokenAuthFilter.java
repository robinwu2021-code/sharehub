package ai.neargo.sharehub.auth;

import ai.neargo.common.data.scope.DataScopeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 两端认证过滤器的共同骨架。运营端 {@link StaffTokenAuthFilter} 与 C 端
 * {@link ConsumerTokenAuthFilter} 的**数据是分开的**（两条 SecurityFilterChain、两套 realm），
 * 分开的是判断，不是流程 —— 流程一模一样，而且其中两步错一次就是事故：
 *
 * <ol>
 *   <li><b>只认 token 反查</b>，绝不信客户端的 X-Roles / X-User-Id（全项目红线）；</li>
 *   <li><b>请求结束必须清干净三个 ThreadLocal</b>。线程池会复用线程，漏清一个，
 *       下一个请求读到的不是「空身份」而是**上一个人的身份**。</li>
 * </ol>
 *
 * 此前这两步在两个文件里各写一遍，而它们已经开始分叉：C 端那份不清
 * {@link CallContext}（它确实没设，但"没设所以不用清"是个会随改动失效的理由）。
 * 收进基类之后，{@code finally} 只有一处，子类想漏也漏不掉。
 *
 * <h3>令牌前缀的两种用法（别混）</h3>
 *
 * {@link TokenStore#newToken} 按 realm 打前缀：{@code stk_} / {@code atk_} / {@code ctk_}。
 * 前缀是**客户端手里的字符串**，所以：
 *
 * <ul>
 *   <li><b>前缀永远不是鉴权依据。</b>判断用的始终是会话里的 realm —— 那是服务端写的。
 *       把前缀当依据等于让调用方自己声明自己是谁。</li>
 *   <li><b>前缀只做两件事：快速排除，和一致性自检。</b>
 *       前者省掉一次存储查询（{@code ctk_} 打到运营端链上不必回库）；
 *       后者见下。</li>
 * </ul>
 *
 * <h3>前缀与会话 realm 不一致 = 发令牌那头有 bug，拒绝并 ERROR</h3>
 *
 * 前缀是 token 字符串的一部分，而 token 就是查询键 —— 改了前缀就查不到会话。
 * 所以两者不一致**不可能是攻击**，只可能是发放侧写错了 realm。
 * 今天四个存储都用 {@code data.user().realm()} 发放，一致性是成立的。
 *
 * <p>选择拒绝而不是"记一笔然后放行"：放行的话，这种 bug 会以"某个消费者令牌
 * 莫名其妙有运营端权限"的形式在几个月后出现，那时已经没人记得是哪次改动引入的。
 * 拒绝是一个当场可归因的 401，代价是重新登录。
 */
public abstract class AbstractTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AbstractTokenAuthFilter.class);

    protected final TokenStore tokenStore;

    protected AbstractTokenAuthFilter(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    /** 本链接受哪些身份域。**唯一的鉴权判断**，依据是会话里的 realm，不是前缀。 */
    protected abstract boolean accepts(Realm realm);

    /** 认证成功后挂什么权限。运营端=权限码，C 端=固定 ROLE_CONSUMER（C 端无 RBAC）。 */
    protected abstract List<? extends GrantedAuthority> authorities(LoginUser user);

    /**
     * 会话就绪后、写入上下文之前的钩子。运营端用它做口径 B 的权限重建。
     * <p>放在写上下文**之前**是有意的：钩子抛异常时，三个 ThreadLocal 一个都还没设，
     * 不存在"设了一半"的中间态。
     *
     * @return 刷新后的会话；<b>返回 {@code null} = 该会话已失效</b>（主体离职/停用），
     *         本次请求按**未认证**处理。给钩子这个出口，是因为「令牌本身有效、
     *         但持有它的人已经不该进来了」是一种真实状态 —— 从前它无处表达，
     *         于是停用一个员工之后他手里的令牌照样能用。
     */
    protected TokenStore.SessionData onSession(String token, TokenStore.SessionData data) {
        return data;
    }

    /** 是否把原始 token 存进 {@link CallContext} 供跨服务透传。默认否。 */
    protected boolean carriesToken() {
        return false;
    }

    @Override
    protected final void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        boolean authenticated = false;
        String token = bearer(req);
        // 前缀已经明确不属于本链 → 连查都不用查
        if (token != null && mayBelongHere(token)) {
            TokenStore.SessionData d = tokenStore.get(token).orElse(null);
            if (d != null && accepts(d.user().realm()) && prefixAgrees(token, d.user())) {
                // 返回 null = 主体已失效（离职/停用）→ 不认证，后续由 Security 出 401/403。
                // **必须嵌在这一层里**：把它拆成平级的第二个 if 会让「realm 不对 / 前缀不符」
                // 的会话也落进认证分支 —— 那是把一道门改成了摆设。
                d = onSession(token, d);
                if (d != null) {
                    LoginUser u = d.user();
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(u, null, authorities(u)));
                    // 与 SecurityContext 成对写：后者只在 web 请求线程上有值，
                    // CurrentUser 让非 web 线程（@Async、定时任务）也读得到当前身份
                    CurrentUser.set(u);
                    DataScopeContext.set(u.dataScope());   // 供 DataScopeHandler 注入 SQL
                    if (carriesToken()) CallContext.setToken(token);
                    authenticated = true;
                }
            }
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            if (authenticated) {
                // 三个一起清。谁设的谁清听起来更整洁，但那正是此前分叉的地方 ——
                // 少清一个的症状是「下一个请求变成了上一个人」，且只在有并发时出现。
                DataScopeContext.clear();
                CurrentUser.clear();
                CallContext.clear();
            }
        }
    }

    private static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 快速排除：前缀能认出来、且对应的 realm 本链不收 → 不必回存储查。
     * <p><b>认不出前缀时一律放行去查</b>，由会话 realm 说了算 —— 这里的职责是省一次查询，
     * 不是把关。按前缀把关等于把「你是谁」的判断交给调用方。
     */
    private boolean mayBelongHere(String token) {
        Realm r = TokenStore.realmOfPrefix(token);
        return r == null || accepts(r);
    }

    private boolean prefixAgrees(String token, LoginUser user) {
        Realm r = TokenStore.realmOfPrefix(token);
        if (r == null || r == user.realm()) return true;
        // 只打前缀，**不打 token 本身** —— 它是一个当前有效的凭据，进了日志就等于多了一份副本。
        // 带上 userNo 是为了能定位是哪条登录路径发错的（规范：异常必须带业务键）。
        log.error("令牌前缀与会话身份域不符：前缀={} 会话realm={} userNo={}。"
                        + "这不可能是伪造（前缀是查询键的一部分，改了就查不到会话），"
                        + "只可能是发放 token 时 realm 写错了。本次按拒绝处理，查 TokenStore.issue 的调用方。",
                TokenStore.prefixOf(token), user.realm(), user.userNo());
        return false;
    }
}
