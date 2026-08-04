package ai.neargo.sharehub.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话/Token 存储 SPI（可切换 memory/ehcache/redis/mysql，见 docs/technical/权限体系设计.md §7）。
 * 认证过滤器与登录只依赖本接口，切换后端零改代码（{@code powerbank.auth.token-store}）。
 */
public interface TokenStore {

    /** 会话数据（token 关联）：主体 + 角色（重建用）+ 权限版本戳（口径 B）。 */
    record SessionData(LoginUser user, List<String> roleNos, long permStamp) {
    }

    /** 发放 token 并存入会话（返回带 realm 前缀的不透明 token）。 */
    String issue(SessionData data);

    /** 反查会话（不存在/过期 → empty）。 */
    Optional<SessionData> get(String token);

    /** 原地刷新会话（口径 B：权限变更后重建覆盖）。 */
    void refresh(String token, SessionData data);

    /** 吊销（登出/踢下线）。 */
    void revoke(String token);

    /** 生成带 realm 前缀的不透明 token。 */
    static String newToken(Realm realm) {
        String p = switch (realm) {
            case CONSUMER -> "ctk_";
            case AGENT -> "atk_";
            default -> "stk_";
        };
        return p + UUID.randomUUID().toString().replace("-", "");
    }
}
