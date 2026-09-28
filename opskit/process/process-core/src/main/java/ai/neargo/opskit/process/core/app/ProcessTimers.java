package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.store.dao.InstanceDao;
import ai.neargo.opskit.process.store.dao.TaskDao;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务的入口（由启动模块注册到 db-scheduler）。定时 ID 只带空间（{@code ws:key:epoch}），
 * 账户从行上取 —— 流程表每行都带 account_id，不必为此去问平台。
 */
public class ProcessTimers {

    private static final int DEFAULT_CLAIM_TIMEOUT_SEC = 1800;

    private final TaskAppService tasks;
    private final GateTimersAndConfirmations gates;
    private final TaskDao taskRows;
    private final InstanceDao instanceRows;
    private final Directory directory;
    private final Clock clock;

    public ProcessTimers(TaskAppService tasks, GateTimersAndConfirmations gates, TaskDao taskRows, InstanceDao instanceRows, Directory directory,
            Clock clock) {
        this.tasks = tasks;
        this.gates = gates;
        this.taskRows = taskRows;
        this.instanceRows = instanceRows;
        this.directory = directory;
        this.clock = clock;
    }

    @SystemAction("定时触发：领取超时")
    public boolean claimTimeout(String timerId) {
        TimerId id = TimerId.decode(timerId);
        return taskRows.find(id.workspace(), id.key())
                .map(r -> tasks.onClaimTimeout(new TenantContext(new AccountId(r.accountId()), id.workspace()), id.key(), id.epoch()))
                .orElse(false);
    }

    @SystemAction("定时触发：外部确认超时")
    public boolean confirmTimeout(String timerId) {
        TimerId id = TimerId.decode(timerId);
        return tenantOfInstance(id).map(t -> gates.onConfirmTimeout(t, id.key(), id.epoch())).orElse(false);
    }

    @SystemAction("定时触发：定时门到点")
    public boolean gateTimer(String timerId) {
        TimerId id = TimerId.decode(timerId);
        return tenantOfInstance(id).map(t -> gates.onGateTimer(t, id.key(), id.epoch())).orElse(false);
    }

    /**
     * 兜底扫描（21/02 §九）：调度丢了也不会有任务永远被占着。取领取已久的任务，每条按自己队列组的领取超时判断，
     * 到了才放回（放回仍走 epoch 比对，与正常超时不会重复）。
     *
     * @return 放回的条数
     */
    @SystemAction("定时兜底")
    public int sweepStaleClaims(int limit) {
        long now = clock.millis();
        int released = 0;
        List<TaskDao.Row> stale = tasks.staleClaims(60_000L, limit);
        for (TaskDao.Row r : stale) {
            TenantContext t = new TenantContext(new AccountId(r.accountId()), new ai.neargo.opskit.commons.tenancy.WorkspaceId(r.workspaceId()));
            int sec = r.queueGroup() == null ? DEFAULT_CLAIM_TIMEOUT_SEC : directory.claimTimeoutSec(t, r.queueGroup()).orElse(DEFAULT_CLAIM_TIMEOUT_SEC);
            if (r.claimedAtMs() != null && r.claimedAtMs() + sec * 1000L <= now && tasks.onClaimTimeout(t, r.taskNo(), r.claimEpoch())) {
                released++;
            }
        }
        return released;
    }

    private Optional<TenantContext> tenantOfInstance(TimerId id) {
        return instanceRows.find(id.workspace(), id.key()).map(r -> new TenantContext(new AccountId(r.accountId()), id.workspace()));
    }
}
