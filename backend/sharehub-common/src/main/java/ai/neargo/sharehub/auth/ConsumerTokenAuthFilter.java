package ai.neargo.sharehub.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * C 端认证过滤器（与运营端 {@link StaffTokenAuthFilter} 分离：两条 SecurityFilterChain、
 * 两套 realm、两份数据）：{@code Bearer} → {@link TokenStore.SessionData}，只认
 * realm=CONSUMER，principal={@link LoginUser}，authorities=ROLE_CONSUMER。
 *
 * <p>C 端**无 RBAC** —— 授权靠 {@link ConsumerContext} 的属主断言（或把
 * {@code c_user_no} 下推到查询条件里，后者更好：查不到即不存在，连单号存在性都不泄露）。
 * 所以这里挂一个固定角色就够了，也没有口径 B 的权限刷新。
 *
 * <p>流程部分全在 {@link AbstractTokenAuthFilter}。此前这个类自己写了一遍，
 * 且已经与运营端那份分叉（不清 {@link CallContext}）—— 收进基类之后不会再分叉。
 */
@Component
public class ConsumerTokenAuthFilter extends AbstractTokenAuthFilter {

    private static final List<SimpleGrantedAuthority> CONSUMER =
            List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"));

    public ConsumerTokenAuthFilter(TokenStore tokenStore) {
        super(tokenStore);
    }

    @Override
    protected boolean accepts(Realm realm) {
        return realm == Realm.CONSUMER;
    }

    @Override
    protected List<SimpleGrantedAuthority> authorities(LoginUser user) {
        return CONSUMER;
    }
}
