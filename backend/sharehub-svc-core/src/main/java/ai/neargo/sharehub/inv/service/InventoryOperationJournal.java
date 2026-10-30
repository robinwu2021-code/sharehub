package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.Json;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 库存外部写入恢复日志。业务事务持有操作行锁；独立事务保存意图及远程步骤，
 * 所以本地业务回滚不会丢失已发出的远程请求。完成标记与本地业务单据同事务提交。
 * 不自动重试未知外部协议；调用方只能接入已保证同键同体重放的库存接口。
 */
@Service
public class InventoryOperationJournal {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate durable;
    private final TransactionTemplate business;
    private final ReentrantLock writeGate = new ReentrantLock(true);

    public InventoryOperationJournal(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        durable = new TransactionTemplate(transactions);
        durable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        business = new TransactionTemplate(transactions);
        business.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Operation(String operationNo, String bizRef, String engine, String phase) { }
    private record Intent(Operation operation, String hash, String actor) { }

    /**
     * 先排队再开业务事务：避免N个业务事务占满连接池、全部等待独立检查点连接。
     * 本实例的恢复作业串行，跨实例仍由数据库操作行锁保护。
     */
    public <T> T execute(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Inventory orchestration must start outside an existing transaction");
        boolean acquired;
        try {
            acquired = writeGate.tryLock(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.conflict("error.inventory.operation_busy");
        }
        if (!acquired) throw BizException.conflict("error.inventory.operation_busy");
        try {
            return business.execute(tx -> action.get());
        } finally {
            writeGate.unlock();
        }
    }

    public String payload(Object body) {
        return Json.write(body);
    }

    /** 独立读取已固定的引擎；不受调用方事务快照影响。 */
    public Operation find(String kind, String key, String payload) {
        return durable.execute(tx -> checked(read(kind, key, false), payload));
    }

    public Operation lock(String kind, String key, String payload, String engine, String bizPrefix) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Inventory operation requires a business transaction");
        try {
            durable.executeWithoutResult(tx -> {
                if (read(kind, key, false) != null) return;
                jdbc.update("INSERT INTO inv_operation (operation_no,tenant_id,inventory_owner_ref,request_key,payload_hash,payload,engine,kind,biz_ref,created_by) VALUES (?,?,'',?,?,?,?,?,?,?)",
                        IdGenerator.next("IO"), SecurityUtils.tenantId(), key, hash(payload), payload, engine, kind,
                        IdGenerator.next(bizPrefix), SecurityUtils.userNo());
            });
        } catch (DuplicateKeyException concurrentRequest) {
            // 另一实例先登记同键；下面持行锁查证请求体及操作者。
            if (find(kind, key, payload) == null) throw concurrentRequest;
        }
        Operation op = checked(read(kind, key, true), payload);
        if (op == null) throw new IllegalStateException("Inventory operation disappeared");
        if (!"COMPLETED".equals(op.phase())) {
            jdbc.update("UPDATE inv_operation SET attempts=attempts+1,last_error=NULL WHERE operation_no=?", op.operationNo());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) return;
                    // 一步远端都没登记（步骤在发请求前先落 UNKNOWN）= 发请求前就被拒，没有可查回的，记终态 REJECTED；
                    // 发出过才是 NEEDS_REVIEW。两种都可以用同键原样重提
                    durable.executeWithoutResult(tx -> {
                        // 只有「结果不明」或「已完成」的步骤才需要查回；库存服务明确拒绝的（FAILED）等于没执行
                        boolean sent = jdbc.queryForObject("SELECT COUNT(*) FROM inv_operation_step WHERE operation_no=? AND status IN ('UNKNOWN','COMPLETED')",
                                Integer.class, op.operationNo()) > 0;
                        jdbc.update("UPDATE inv_operation SET phase=?,attempts=attempts+1,last_error=? WHERE operation_no=? AND phase<>'COMPLETED'",
                                sent ? "NEEDS_REVIEW" : "REJECTED",
                                sent ? "Business transaction rolled back; retry the original request key and payload"
                                        : "Rejected before any inventory-service request; nothing to recover", op.operationNo());
                    });
                }
            });
        }
        return op;
    }

    private Intent read(String kind, String key, boolean lock) {
        List<Intent> found = jdbc.query("SELECT operation_no,biz_ref,engine,phase,payload_hash,created_by FROM inv_operation WHERE tenant_id=? AND inventory_owner_ref='' AND kind=? AND request_key=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Intent(new Operation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), rs.getString(5), rs.getString(6)),
                SecurityUtils.tenantId(), kind, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private Operation checked(Intent intent, String payload) {
        if (intent == null) return null;
        if (!java.util.Objects.equals(intent.actor(), SecurityUtils.userNo())) throw new AccessDeniedException("inventory operation owner");
        if (!intent.hash().equals(hash(payload))) throw BizException.conflict("error.inventory.request_changed");
        return intent.operation();
    }

    /** UNKNOWN在发请求之前落盘；重试依赖远端的同键同体幂等，不能据异常猜测未过账。 */
    public String step(Operation op, String code, Supplier<String> action) {
        String key = op.operationNo() + "-" + code;
        List<String> result = durable.execute(tx -> jdbc.query("SELECT remote_ref FROM inv_operation_step WHERE operation_no=? AND step_code=? AND status='COMPLETED'",
                (rs, row) -> rs.getString(1), op.operationNo(), code));
        if (result != null && !result.isEmpty()) return result.getFirst();
        durable.executeWithoutResult(tx -> jdbc.update("INSERT INTO inv_operation_step (operation_no,step_code,request_key,status) VALUES (?,?,?,'UNKNOWN') ON DUPLICATE KEY UPDATE status='UNKNOWN'",
                op.operationNo(), code, key));
        String remoteRef;
        try {
            remoteRef = action.get();
        } catch (ai.neargo.sharehub.inv.remote.InventoryRemoteException e) {
            // 4xx = 库存服务明确拒绝、没有执行 → FAILED；5xx / 不可达仍是 UNKNOWN（不能据此猜没执行）
            if (e.status >= 400 && e.status < 500) {
                String why = e.code == null ? "rejected" : e.code.length() > 200 ? e.code.substring(0, 200) : e.code;
                durable.executeWithoutResult(tx -> jdbc.update("UPDATE inv_operation_step SET status='FAILED',last_error=? WHERE operation_no=? AND step_code=?",
                        why, op.operationNo(), code));
            }
            throw e;
        }
        durable.executeWithoutResult(tx -> jdbc.update("UPDATE inv_operation_step SET status='COMPLETED',remote_ref=?,last_error=NULL WHERE operation_no=? AND step_code=?",
                remoteRef, op.operationNo(), code));
        return remoteRef;
    }

    public String stepKey(Operation op, String code) { return op.operationNo() + "-" + code; }

    /** 外部已建立的主数据引用也必须独立提交，否则失败后的余额查询会丢失映射。 */
    public <T> T reference(Supplier<T> sync) { return durable.execute(tx -> sync.get()); }

    /** 未完成操作仍依赖这些主数据；不能归档或改写后让原请求无法恢复。路径只由领域服务常量传入。 */
    public void requireMasterIdle(String no, String... paths) {
        for (String path : paths) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM inv_operation WHERE tenant_id=? AND kind='MATERIAL_DOC' AND phase NOT IN ('COMPLETED','REJECTED') AND JSON_CONTAINS(JSON_EXTRACT(payload,?),JSON_QUOTE(?))", Integer.class,
                    SecurityUtils.tenantId(), path, no);
            if (count != null && count > 0) throw BizException.conflict("error.inventory.master_pending", no);
        }
    }

    /**
     * 未完成的库存操作数（待查回、待复核；发请求前就被拒的 REJECTED 不算）。
     * 切换引擎前须为 0：在途操作按其固定的引擎恢复，切走就恢复不了。
     */
    public long unfinished() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM inv_operation WHERE tenant_id=? AND phase NOT IN ('COMPLETED','REJECTED')", Long.class,
                SecurityUtils.tenantId());
        return n == null ? 0 : n;
    }

    public boolean attempted(Operation op, String code) {
        return Boolean.TRUE.equals(durable.execute(tx -> jdbc.queryForObject("SELECT COUNT(*) FROM inv_operation_step WHERE operation_no=? AND step_code=?", Integer.class,
                op.operationNo(), code) > 0));
    }

    public void complete(Operation op) {
        jdbc.update("UPDATE inv_operation SET phase='COMPLETED',last_error=NULL,next_retry_at=NULL WHERE operation_no=?", op.operationNo());
    }

    private static String hash(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
