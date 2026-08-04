package ai.neargo.sharehub.auth;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局权限版本号（口径 B）。任何角色权限/数据范围配置变更 → {@link #bump()}；
 * {@link StaffTokenAuthFilter} 每请求比对会话戳与此值，变了则重载会话权限 → **改权限即时生效**。
 */
@Component
public class PermVersion {

    private final AtomicLong version = new AtomicLong(1);

    public long get() {
        return version.get();
    }

    public long bump() {
        return version.incrementAndGet();
    }
}
