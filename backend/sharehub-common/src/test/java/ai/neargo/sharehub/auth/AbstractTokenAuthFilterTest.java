package ai.neargo.sharehub.auth;

import ai.neargo.common.data.scope.DataScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两端认证过滤器的共同骨架。这里测的都是**出错时不会有人看见**的事：
 * 前缀对账、少查一次库、请求结束把 ThreadLocal 清干净。
 *
 * <p>不起 Spring 容器 —— 这些行为一个 bean 都不依赖，起容器只会让它从毫秒变成十几秒，
 * 而慢测试的下场是没人在本地跑。
 */
class AbstractTokenAuthFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        CurrentUser.clear();
        CallContext.clear();
        DataScopeContext.clear();
    }

    // ——————————————————————— 前缀 ↔ realm 对账 ———————————————————————

    @Test
    void a_token_whose_prefix_contradicts_its_session_is_refused() {
        // 前缀是查询键的一部分，改了就查不到会话 —— 所以不一致不可能是伪造，
        // 只能是发放侧把 realm 写错了。放行的话，这个 bug 会在几个月后以
        // 「某个令牌莫名其妙有运营端权限」的形式出现，那时已无从归因。
        //
        // **必须用同一条链内部的 STAFF/AGENT 混淆来测。**第一版这里用的是 ctk_ 配
        // STAFF 会话，测试是绿的 —— 但绿的原因是快速排除那层先把 ctk_ 挡掉了，
        // 对账代码压根没执行。反向验证（把对账整段删掉）一个测试都不红才发现。
        // 跨链的情况本来就轮不到对账；对账真正管的是这里。
        LoginUser staff = staff("U1");
        CountingStore store = new CountingStore(Map.of("atk_wrongly_minted", session(staff)));

        assertThat(authenticate(new StaffLike(store), "atk_wrongly_minted"))
                .as("atk_ 前缀配 STAFF 会话：两者都被运营端链接受，只能靠对账拦").isNull();
        assertThat(store.lookups).as("这条路径必须真查过存储，否则测的又是快速排除").isOne();
    }

    @Test
    void a_consumer_prefixed_token_never_reaches_the_ops_chain_at_all() {
        // 跨链的那一半：被快速排除挡在存储查询之前。与上面那条是**不同的两道**，
        // 分开测才看得出哪道在起作用。
        LoginUser staff = staff("U1");
        CountingStore store = new CountingStore(Map.of("ctk_wrongly_minted", session(staff)));

        assertThat(authenticate(new StaffLike(store), "ctk_wrongly_minted")).isNull();
        assertThat(store.lookups).isZero();
    }

    @Test
    void a_token_with_a_matching_prefix_authenticates() {
        LoginUser staff = staff("U1");
        CountingStore store = new CountingStore(Map.of("stk_ok", session(staff)));

        assertThat(authenticate(new StaffLike(store), "stk_ok")).isEqualTo(staff);
    }

    @Test
    void an_unrecognised_prefix_is_decided_by_the_session_not_by_the_prefix() {
        // 认不出前缀时必须放行去查，由会话说了算。反过来「前缀不认识就拒绝」
        // 等于把「你是谁」的判断权交给调用方手里的那个字符串。
        LoginUser staff = staff("U1");
        CountingStore store = new CountingStore(Map.of("legacy-no-prefix", session(staff)));

        assertThat(authenticate(new StaffLike(store), "legacy-no-prefix")).isEqualTo(staff);
    }

    // ——————————————————————— 快速排除 ———————————————————————

    @Test
    void a_consumer_token_on_the_ops_chain_does_not_even_hit_the_store() {
        // 省的是一次存储查询（MySQL 存储下就是一次真查库）。
        // 注意这是**性能**优化不是安全措施 —— 安全那一层是下面 accepts(realm)。
        CountingStore store = new CountingStore(Map.of());

        assertThat(authenticate(new StaffLike(store), "ctk_someconsumer")).isNull();
        assertThat(store.lookups).as("前缀已经说明它不属于本链，不该回存储查").isZero();
    }

    @Test
    void a_token_without_a_recognisable_prefix_still_hits_the_store() {
        CountingStore store = new CountingStore(Map.of());

        assertThat(authenticate(new StaffLike(store), "legacy-no-prefix")).isNull();
        assertThat(store.lookups).as("认不出前缀就必须查，否则等于按前缀把关").isOne();
    }

    // ——————————————————————— ThreadLocal 清理 ———————————————————————

    @Test
    void every_thread_local_is_cleared_when_the_request_ends() {
        // 线程池复用线程。漏清一个，下一个请求读到的不是「空身份」而是**上一个人的身份**，
        // 而且只在有并发时出现 —— 本地永远复现不了。
        CountingStore store = new CountingStore(Map.of("stk_ok", session(staff("U1"))));

        authenticate(new StaffLike(store), "stk_ok");

        assertThat(CurrentUser.get()).as("CurrentUser 未清").isEmpty();
        assertThat(CallContext.token()).as("CallContext 未清").isNull();
        assertThat(DataScopeContext.current()).as("DataScopeContext 未清").isNull();
    }

    @Test
    void the_thread_locals_are_actually_set_while_the_request_runs() {
        // 上一条只证明「最后是空的」——空也可能是因为**从来没设过**。
        // 这条证明设过，两条合起来才说明清理真的发生了。
        CountingStore store = new CountingStore(Map.of("stk_ok", session(staff("U1"))));
        List<String> seen = new ArrayList<>();
        StaffLike f = new StaffLike(store);

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                seen.add(CurrentUser.get().map(LoginUser::userNo).orElse("(空)"));
                seen.add(CallContext.token() == null ? "(空)" : "有 token");
            }
        };
        run(f, "stk_ok", chain);

        assertThat(seen).containsExactly("U1", "有 token");
    }

    @Test
    void a_failed_authentication_leaves_nothing_behind_either() {
        CountingStore store = new CountingStore(Map.of());

        authenticate(new StaffLike(store), "stk_unknown");

        assertThat(CurrentUser.get()).isEmpty();
        assertThat(CallContext.token()).isNull();
    }

    // ——————————————————————— 前缀表本身 ———————————————————————

    @Test
    void every_realm_has_a_distinct_prefix_that_round_trips() {
        // 发放与解析共用 prefixFor。这条测的是那张表本身自洽：
        // 每个 realm 一个前缀、彼此不同、且解析回得去。
        // 两个 realm 共用前缀的话，对账会把正常令牌判成不一致。
        List<String> prefixes = new ArrayList<>();
        for (Realm r : Realm.values()) {
            String p = TokenStore.prefixFor(r);
            prefixes.add(p);
            assertThat(TokenStore.realmOfPrefix(TokenStore.newToken(r)))
                    .as("%s 发出来的 token 应能解析回 %s", r, r).isEqualTo(r);
        }
        assertThat(prefixes).as("前缀不能重复").doesNotHaveDuplicates();
    }

    // ——————————————————————— 脚手架 ———————————————————————

    private static LoginUser staff(String no) {
        return new LoginUser(Realm.STAFF, no, "张三", "ADMIN", List.of("*"), "MAIN", null, null);
    }

    private static TokenStore.SessionData session(LoginUser u) {
        return new TokenStore.SessionData(u, List.of(), 0L);
    }

    /** 跑一次过滤器，返回被认证的主体（未认证 → null）。 */
    private static LoginUser authenticate(AbstractTokenAuthFilter f, String token) {
        return run(f, token, new MockFilterChain());
    }

    private static LoginUser run(AbstractTokenAuthFilter f, String token, MockFilterChain chain) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        try {
            f.doFilter(req, new MockHttpServletResponse(), chain);
        } catch (Exception e) {
            throw new IllegalStateException("过滤器不该抛异常", e);
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (LoginUser) auth.getPrincipal();
    }

    /** 数查询次数的假存储 —— 「不该回库」这件事只能靠数次数证明。 */
    private static class CountingStore implements TokenStore {
        private final Map<String, SessionData> data;
        int lookups;

        CountingStore(Map<String, SessionData> data) {
            this.data = new HashMap<>(data);
        }

        @Override
        public String issue(SessionData d) {
            String t = TokenStore.newToken(d.user().realm());
            data.put(t, d);
            return t;
        }

        @Override
        public Optional<SessionData> get(String token) {
            lookups++;
            return Optional.ofNullable(data.get(token));
        }

        @Override
        public void refresh(String token, SessionData d) {
            data.put(token, d);
        }

        @Override
        public void revoke(String token) {
            data.remove(token);
        }
    }

    /** 与 StaffTokenAuthFilter 相同的取舍（收 STAFF/AGENT、透传 token），但不牵扯 IAM 依赖。 */
    private static class StaffLike extends AbstractTokenAuthFilter {
        StaffLike(TokenStore s) {
            super(s);
        }

        @Override
        protected boolean accepts(Realm realm) {
            return realm != Realm.CONSUMER;
        }

        @Override
        protected List<SimpleGrantedAuthority> authorities(LoginUser u) {
            return u.perms().stream().map(SimpleGrantedAuthority::new).toList();
        }

        @Override
        protected boolean carriesToken() {
            return true;
        }
    }
}
