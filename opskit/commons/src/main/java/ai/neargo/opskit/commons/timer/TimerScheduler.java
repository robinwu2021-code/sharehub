package ai.neargo.opskit.commons.timer;

import java.time.Instant;

/**
 * 持久化定时的抽象（实现在 platform-core，底层是 db-scheduler）。
 *
 * <p>没有 cancel：重排靠 epoch 让旧任务自然作废，正确性不依赖「取消成功」。
 */
public interface TimerScheduler {

    /** 同名同 ID 已存在时覆盖执行时间（重复调度幂等）。 */
    void schedule(String taskName, TimerId id, Instant at);
}
