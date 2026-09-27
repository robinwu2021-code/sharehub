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
}
