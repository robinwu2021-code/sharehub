package ai.neargo.powerbank.auth.store;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.powerbank.auth.TokenStore;
import ai.neargo.powerbank.auth.store.SysToken.SysTokenMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * MySQL TokenStore（{@code token-store=mysql}）：会话落 {@code sys_token} 表——**强持久、可查会话、多实例共享**，
 * 性能次于内存/Redis（每请求一次 DB），生产建议叠加 Caffeine L1。过期靠 expire_at + 清理任务。
 * 会话查询以 {@link DataScopeContext#executeWithoutScope} 豁免数据权限拦截（无登录人上下文）。
 */
public class MysqlTokenStore implements TokenStore {

    private final SysTokenMapper mapper;
    private final ObjectMapper om;
    private final Duration ttl;

    public MysqlTokenStore(SysTokenMapper mapper, ObjectMapper om, Duration ttl) {
        this.mapper = mapper;
        this.om = om;
        this.ttl = ttl;
    }

    @Override
    public String issue(SessionData data) {
        String token = TokenStore.newToken(data.user().realm());
        SysToken r = new SysToken();
        r.setToken(token);
        r.setRealm(data.user().realm().name());
        r.setSubjectNo(data.user().userNo());
        r.setRoleNos(String.join(",", data.roleNos()));
        r.setPermStamp(data.permStamp());
        r.setPayload(toJson(data));
        r.setExpireAt(LocalDateTime.now().plus(ttl));
        DataScopeContext.executeWithoutScope(() -> mapper.insert(r));
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        if (token == null) {
            return Optional.empty();
        }
        SysToken r = DataScopeContext.executeWithoutScope(() -> mapper.selectById(token));
        if (r == null) {
            return Optional.empty();
        }
        if (r.getExpireAt() != null && r.getExpireAt().isBefore(LocalDateTime.now())) {
            DataScopeContext.executeWithoutScope(() -> mapper.deleteById(token));
            return Optional.empty();
        }
        return Optional.of(fromJson(r.getPayload()));
    }

    @Override
    public void refresh(String token, SessionData data) {
        if (token == null) {
            return;
        }
        SysToken r = new SysToken();
        r.setToken(token);
        r.setPermStamp(data.permStamp());
        r.setPayload(toJson(data));
        r.setExpireAt(LocalDateTime.now().plus(ttl));
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(r));
    }

    @Override
    public void revoke(String token) {
        if (token != null) {
            DataScopeContext.executeWithoutScope(() -> mapper.deleteById(token));
        }
    }

    private String toJson(SessionData d) {
        try {
            return om.writeValueAsString(d);
        } catch (Exception e) {
            throw new IllegalStateException("会话序列化失败", e);
        }
    }

    private SessionData fromJson(String v) {
        try {
            return om.readValue(v, SessionData.class);
        } catch (Exception e) {
            throw new IllegalStateException("会话反序列化失败", e);
        }
    }
}
