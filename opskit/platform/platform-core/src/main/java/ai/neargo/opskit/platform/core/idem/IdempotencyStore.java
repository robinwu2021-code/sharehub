package ai.neargo.opskit.platform.core.idem;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.dao.IdempotencyDao;
import java.time.Clock;
import java.time.Duration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 写请求幂等（21/06 §二 · 21/07 O1-10）。用法：{@link #begin} → 执行业务 → {@link #complete} 或 {@link #abandon}。
 *
 * <p>「处理中」占位必须**先单独提交**，并发的同键请求才看得见 —— 所以 begin 自己开短事务，不在业务事务里。
 */
public class IdempotencyStore {

    public sealed interface Outcome {
        /** 首次：执行业务。 */
        record Proceed() implements Outcome {
        }

        /** 已完成过：原样返回首次结果（含状态码）。 */
        record Replay(int httpStatus, String body) implements Outcome {
        }
    }

    public static final int MAX_KEY_LENGTH = 128;

    private final IdempotencyDao dao;
    private final Clock clock;
    private final TransactionOperations tx;
    private final Duration retention;

    public IdempotencyStore(IdempotencyDao dao, Clock clock, TransactionOperations tx, Duration retention) {
        this.dao = dao;
        this.clock = clock;
        this.tx = tx;
        this.retention = retention;
    }

    /**
     * @throws OpsKitException 409 {@code opskit.idempotency.mismatch}：同键不同内容；409 {@code opskit.idempotency.in_progress}：同键请求仍在处理
     */
    public Outcome begin(String caller, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new OpsKitException(CommonErrors.VALIDATION, "Idempotency-Key must be 1.." + MAX_KEY_LENGTH + " chars");
        }
        try {
            tx.executeWithoutResult(s -> dao.insertPlaceholder(AccountId.UNKNOWN, caller, key, requestHash, clock.millis()));
            return new Outcome.Proceed();
        } catch (DuplicateKeyException e) {
            // 占位插入的短事务已随异常回滚；另开事务读对方的记录
            IdempotencyDao.Record r = tx.execute(s -> dao.find(AccountId.UNKNOWN, caller, key)).orElse(null);
            if (r == null) {
                // 对方在这一瞬间放弃了占位：让调用方重试即可
                throw new OpsKitException(IN_PROGRESS, "idempotent request state changed, retry");
            }
            if (!r.requestHash().equals(requestHash)) {
                throw new OpsKitException(CommonErrors.IDEMPOTENCY_MISMATCH, "same Idempotency-Key with a different request");
            }
            if (r.httpStatus() == 0) {
                throw new OpsKitException(IN_PROGRESS, "a request with this Idempotency-Key is still being processed");
            }
            return new Outcome.Replay(r.httpStatus(), r.responseBody());
        }
    }

    /** 业务给出了确定的结果（含 4xx 业务错误）：记下，之后同键重放它。 */
    public void complete(String caller, String key, int httpStatus, String responseBody) {
        tx.executeWithoutResult(s -> dao.complete(AccountId.UNKNOWN, caller, key, httpStatus, responseBody));
    }

    /** 意外失败（5xx / 异常）：删掉占位，允许同键重试。 */
    public void abandon(String caller, String key) {
        tx.executeWithoutResult(s -> dao.abandon(AccountId.UNKNOWN, caller, key));
    }

    public int purgeExpired() {
        long before = clock.millis() - retention.toMillis();
        return tx.execute(s -> dao.purgeBefore(AccountId.UNKNOWN, before));
    }

    public static final ai.neargo.opskit.commons.error.ErrorKey IN_PROGRESS =
            new ai.neargo.opskit.commons.error.ErrorKey("opskit.idempotency.in_progress", 409);
}
