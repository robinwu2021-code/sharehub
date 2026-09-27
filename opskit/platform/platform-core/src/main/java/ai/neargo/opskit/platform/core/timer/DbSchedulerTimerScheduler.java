package ai.neargo.opskit.platform.core.timer;

import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.commons.timer.TimerScheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.SchedulerClient.ScheduleOptions;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link TimerScheduler} 的 db-scheduler 实现，全进程共用。
 *
 * <p>任务不带数据：一切信息编进 task_instance（{@link TimerId#encode()}），不走 db-scheduler 的序列化
 * （默认是 Java 序列化，表里不可读、类一改就反序列化失败）。同 ID 再次调度 = 改执行时间。
 *
 * <p>调度写库经 db-scheduler 包过的事务感知数据源，会加入调用方的 Spring 事务：业务回滚，调度也回滚
 * （由 DbSchedulerTimerSchedulerTest 钉住）。即便将来这一点变了，epoch 与兜底扫描也保证结果正确（21/02 §九）。
 */
public final class DbSchedulerTimerScheduler implements TimerScheduler {

    private final Supplier<? extends SchedulerClient> client;

    /**
     * 按需取调度器：定时任务依赖业务服务、业务服务又依赖本类，直接注入 Scheduler 会形成创建期循环。
     */
    public DbSchedulerTimerScheduler(Supplier<? extends SchedulerClient> client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void schedule(String taskName, TimerId id, Instant at) {
        client.get().schedule(new TaskInstance<Void>(taskName, id.encode()), at, ScheduleOptions.WHEN_EXISTS_RESCHEDULE);
    }
}
