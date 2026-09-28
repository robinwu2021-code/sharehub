package ai.neargo.opskit.commons.event;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.time.Instant;

/**
 * 领域事件。同事务内先分发给进程内订阅者，再写进发件箱对外投递（02 §8.7）。
 * {@link #idemKey()} 是对外的幂等键（如 {@code instanceNo:seq}），接收方据此去重。
 */
public interface DomainEvent {

    TenantContext tenant();

    String eventType();

    String idemKey();

    Instant occurredAt();

    /** 对外载荷里的 {@code data}（21/06 §六）。只放接收方需要的业务字段，不重复信封里已有的租户与时间。 */
    Object data();

    /**
     * data 里值为操作人 / 成员主体（{@code member:…} · {@code app:…} · {@code system}）的顶层字段名。
     * 写发件箱时这些字段被展开成 {@code {ref, externalRef?, displayName?}}，接收方不用再反查成员（21/06 §六）；
     * 进程内订阅者收到的仍是事件对象本身，不受影响。
     */
    default java.util.Set<String> memberFields() {
        return java.util.Set.of();
    }
}
