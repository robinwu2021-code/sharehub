package ai.neargo.opskit.platform.core.outbox;

import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.dao.OutboxDao;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 发件箱保留期：已送达超过保留期的事件连同投递记录清掉（每日任务 outbox-purge）。失败待人工与仍在重试的永远不清 ——
 * 它们要等人处理。事件信封里可能有外部确认的一次性令牌明文，不宜无限期留存。
 */
public final class OutboxRetention {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);
    private static final int BATCH = 1000;

    private OutboxRetention() {
    }

    /** 分批、每批单独提交（不在一个大事务里锁住整张表）。@return 清掉的事件数 */
    public static int purge(OutboxDao outbox, Clock clock, TransactionOperations tx, Duration retention) {
        long before = clock.millis() - retention.toMillis();
        int total = 0;
        int n;
        do {
            n = tx.execute(s -> outbox.purgeDeliveredBefore(AccountId.UNKNOWN, before, BATCH));
            total += n;
        } while (n == BATCH);
        if (total > 0) {
            log.info("清理已送达事件 {} 条（保留期 {}）", total, retention);
        }
        return total;
    }
}
