package ai.neargo.opskit.connector;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已处理事件的台账（按 {@code eventType + idemKey} 去重）。OpsKit 至少投递一次：同一事件可能重投多次，
 * 业务只能推进一次。生产实现用数据库唯一键（如 {@code INSERT … ON DUPLICATE KEY} 判是否首次），
 * 并与业务推进同一事务更好 —— 那样处理失败时回滚，重投能再处理。
 */
public interface EventLedger {

    /** 首次见到返回 true 并记下；已记过返回 false。必须原子。 */
    boolean firstTime(String eventType, String idemKey);

    /** 处理失败时撤销记录，让重投能再处理（与业务同事务的实现可以不做任何事）。 */
    void forget(String eventType, String idemKey);

    /** 进程内实现：测试与单实例演示用，重启即丢。 */
    static EventLedger inMemory() {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        return new EventLedger() {
            @Override
            public boolean firstTime(String eventType, String idemKey) {
                return seen.add(eventType + " " + idemKey);
            }

            @Override
            public void forget(String eventType, String idemKey) {
                seen.remove(eventType + " " + idemKey);
            }
        };
    }
}
