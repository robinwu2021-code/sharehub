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

    /**
     * realm → token 前缀。**发放与解析共用这一张表**。
     *
     * <p>此前只有发放侧有这个 switch。一旦解析侧另写一份，两份就会各自演化，
     * 而演化出来的结果正好是 {@link AbstractTokenAuthFilter} 要拦的那种
     * 「前缀与会话 realm 不符」—— 用一个 bug 去触发另一个 bug 的告警，很难查。
     *
     * <p>这里**故意穷举而不写 default**：将来加一个 realm，编译当场失败。
     * 写 {@code default -> "stk_"} 的话，新 realm 会悄悄拿到员工前缀，
     * 而这个错误只会在很久以后以别的形式冒出来。
     */
    static String prefixFor(Realm realm) {
        return switch (realm) {
            case CONSUMER -> "ctk_";
            case AGENT -> "atk_";
            case STAFF -> "stk_";
        };
    }

    /** 生成带 realm 前缀的不透明 token。 */
    static String newToken(Realm realm) {
        return prefixFor(realm) + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 从 token 反推 realm；认不出前缀返回 {@code null}。
     *
     * <p><b>返回值不能当鉴权依据</b> —— 前缀是调用方手里的字符串。
     * 它的用途只有两个：快速排除（省一次存储查询）、与会话 realm 对账（见
     * {@link AbstractTokenAuthFilter}）。
     */
    static Realm realmOfPrefix(String token) {
        if (token == null) return null;
        for (Realm r : Realm.values()) {
            if (token.startsWith(prefixFor(r))) return r;
        }
        return null;
    }

    /** 日志用：只回前缀，绝不回 token 本身（它是有效凭据）。 */
    static String prefixOf(String token) {
        Realm r = realmOfPrefix(token);
        return r == null ? "(无法识别)" : prefixFor(r);
    }
}
