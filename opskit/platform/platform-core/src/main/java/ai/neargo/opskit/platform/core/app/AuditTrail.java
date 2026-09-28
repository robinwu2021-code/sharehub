package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.JsonColumns;
import ai.neargo.opskit.platform.store.dao.AuditDao;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 审计（21/07 O1-11）。必须在被审计的变更的**同一事务**里调用：变更回滚审计也回滚，变更成功审计不会缺。
 */
public final class AuditTrail implements ai.neargo.opskit.commons.audit.AuditRecorder {

    /** 运维面的操作者（M1 的运维凭证不区分个人）。 */
    public static final String OPS = "ops:console";

    private final AuditDao dao;
    private final Clock clock;

    public AuditTrail(AuditDao dao, Clock clock) {
        this.dao = dao;
        this.clock = clock;
    }

    @Override
    public void record(Actor actor, String workspaceId, String action, String target, Map<String, ?> detail) {
        record(actor.tenant().accountId(), workspaceId, who(actor), action, target, detail);
    }

    public void record(Actor actor, String action, String target, Map<String, ?> detail) {
        record(actor.tenant().accountId(), actor.tenant().workspaceId().value(), who(actor), action, target, detail);
    }

    public void record(AccountId account, String workspaceId, String actor, String action, String target, Map<String, ?> detail) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("audit must be written in the audited transaction: " + action);
        }
        dao.insert(account, workspaceId, actor, action, target, JsonColumns.write(detail), clock.millis());
    }

    public List<AuditDao.Entry> query(AccountId account, String workspaceId, long fromMs, long toMs, int limit) {
        return dao.query(account, workspaceId, fromMs, toMs, Math.min(limit, 200));
    }

    /** 应用代成员时记成员并带上应用，事后才能回答「谁经由哪个系统做的」。 */
    static String who(Actor a) {
        return a.member().map(m -> m + a.app().map(app -> " via app:" + app).orElse(""))
                .orElseGet(() -> "app:" + a.app().orElse("?"));
    }

    /**
     * 账户级审计查询（运维面）。时间窗默认最近 30 天、最长 366 天。
     *
     * @param workspaceId 空 = 全部（含账户级操作）；{@code ""} = 只看账户级操作
     */
    public ai.neargo.opskit.commons.query.Page<AuditDao.Entry> query(AccountId account, String workspaceId, String action, Long fromMs, Long toMs,
            ai.neargo.opskit.commons.query.PageRequest page) {
        long to = toMs == null ? clock.millis() + 1 : toMs;
        long from = fromMs == null ? to - java.time.Duration.ofDays(30).toMillis() : fromMs;
        if (to - from > java.time.Duration.ofDays(366).toMillis() || from > to) {
            throw new ai.neargo.opskit.commons.error.OpsKitException(ai.neargo.opskit.commons.error.CommonErrors.VALIDATION, "time window out of range",
                    Map.of(), java.util.List.of(new ai.neargo.opskit.commons.error.FieldError("fromMs", "opskit.validation.out_of_range")));
        }
        long total = dao.countAccount(account, workspaceId, action, from, to);
        return ai.neargo.opskit.commons.query.Page.of(total == 0 ? java.util.List.of() : dao.queryAccount(account, workspaceId, action, from, to,
                page.offset(), page.size()), total, page);
    }
}
