package ai.neargo.opskit.platform.store.dao;

import ai.neargo.opskit.commons.jdbc.Dialect;
import ai.neargo.opskit.commons.tenancy.AccountId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 写请求幂等记录。主键是（调用方, 幂等键）；调用方是 {@code app:{id}} / {@code member:{id}}，全局唯一，
 * 所以这张表没有租户列 —— 第一个参数显式写 {@link AccountId#UNKNOWN}，让「无租户条件」在调用处显形。
 */
public final class IdempotencyDao {

    public record Record(String requestHash, int httpStatus, String responseBody, long atMs) {
    }

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public IdempotencyDao(JdbcClient jdbc, Dialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** 插入「处理中」占位（http_status = 0）。撞主键抛 DuplicateKeyException —— 调用方须在独立短事务里调用并立即结束该事务。 */
    public void insertPlaceholder(AccountId unknown, String caller, String key, String requestHash, long now) {
        requireUnknown(unknown);
        jdbc.sql("INSERT INTO plat_idempotency (caller, idem_key, request_hash, http_status, response_body, at_ms) VALUES (?, ?, ?, 0, NULL, ?)")
                .params(caller, key, requestHash, now).update();
    }

    public Optional<Record> find(AccountId unknown, String caller, String key) {
        requireUnknown(unknown);
        return jdbc.sql("SELECT request_hash, http_status, response_body, at_ms FROM plat_idempotency WHERE caller = ? AND idem_key = ?")
                .params(caller, key)
                .query((rs, i) -> new Record(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getLong(4))).optional();
    }

    public void complete(AccountId unknown, String caller, String key, int httpStatus, String responseBody) {
        requireUnknown(unknown);
        jdbc.sql("UPDATE plat_idempotency SET http_status = ?, response_body = " + dialect.jsonParam() + " WHERE caller = ? AND idem_key = ? AND http_status = 0")
                .params(httpStatus, responseBody, caller, key).update();
    }

    public void abandon(AccountId unknown, String caller, String key) {
        requireUnknown(unknown);
        jdbc.sql("DELETE FROM plat_idempotency WHERE caller = ? AND idem_key = ? AND http_status = 0").params(caller, key).update();
    }

    public int purgeBefore(AccountId unknown, long beforeMs) {
        requireUnknown(unknown);
        return jdbc.sql("DELETE FROM plat_idempotency WHERE at_ms < ?").param(beforeMs).update();
    }

    private static void requireUnknown(AccountId a) {
        if (!AccountId.UNKNOWN.equals(a)) {
            throw new IllegalArgumentException("idempotency records are keyed by caller, not by tenant");
        }
    }
}
