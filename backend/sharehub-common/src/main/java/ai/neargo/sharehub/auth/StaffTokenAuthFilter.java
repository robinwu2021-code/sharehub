package ai.neargo.sharehub.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 运营端（B 端）认证过滤器：{@code Bearer} → {@link TokenStore.SessionData} → {@link LoginUser}
 * → authorities=权限码。只认 STAFF/AGENT 会话。
 *
 * <p>取 token、写上下文、请求结束清 ThreadLocal 这些都在
 * {@link AbstractTokenAuthFilter} 里 —— 那是与 C 端**逐字相同**的部分。
 * 本类只留三处真正不同的：收哪些 realm、挂什么权限、口径 B 的刷新。
 *
 * <p><b>红线</b>：只认 token 反查权限，忽略客户端 X-Roles / X-User-Id。
 *
 * <p><b>口径 B（改权限即时生效）</b>：会话戳 ≠ 全局 {@link PermVersion} 时，
 * 用 {@link PrincipalRefresher#rebuild} 按会话角色重载权限/数据范围并原地刷新会话 ——
 * 在线员工下一请求即收敛新权限。依赖 infra 接口 {@link PrincipalRefresher}
 * （非 IAM 业务 PermissionService），保证分层 infra ← iam（ADR-015）。
 */
@Component
public class StaffTokenAuthFilter extends AbstractTokenAuthFilter {

    private final PrincipalRefresher refresher;
    private final PermVersion permVersion;

    public StaffTokenAuthFilter(TokenStore tokenStore, PrincipalRefresher refresher, PermVersion permVersion) {
        super(tokenStore);
        this.refresher = refresher;
        this.permVersion = permVersion;
    }

    @Override
    protected boolean accepts(Realm realm) {
        return realm != Realm.CONSUMER;
    }

    @Override
    protected List<SimpleGrantedAuthority> authorities(LoginUser user) {
        return user.perms().stream().map(SimpleGrantedAuthority::new).toList();
    }

    /** 口径 B：权限版本变了就按会话角色重建，并把新会话写回存储。 */
    @Override
    protected TokenStore.SessionData onSession(String token, TokenStore.SessionData d) {
        if (d.permStamp() == permVersion.get()) return d;
        LoginUser rebuilt = refresher.rebuild(d.user(), d.roleNos());
        TokenStore.SessionData fresh = new TokenStore.SessionData(rebuilt, d.roleNos(), permVersion.get());
        tokenStore.refresh(token, fresh);
        return fresh;
    }

    /**
     * 跨服务调用要带上原始 token：{@code SecurityContext} 里只有解析后的 {@link LoginUser}，
     * token 本身已经不在了。<b>透传 token 而非身份声明</b>，红线不破 ——
     * 对端拿自己的 {@link TokenStore} 反查，判定仍发生在持有 TokenStore 的那一侧。
     */
    @Override
    protected boolean carriesToken() {
        return true;
    }
}
