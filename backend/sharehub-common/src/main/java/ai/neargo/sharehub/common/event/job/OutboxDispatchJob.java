package ai.neargo.sharehub.common.event.job;

import ai.neargo.sharehub.common.event.OutboxDispatcher;
import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 投递 {@code sys_outbox} 里待发 / 失败的事件（v4/07 §5.1 的 {@code outbox-dispatch}）。
 *
 * <h2>⚠️ 本 handler 上线后，{@code OutboxDispatchScheduler} 仍然要留着</h2>
 * 执行方案原先写的是「outbox-dispatch 接管后删 `OutboxDispatchScheduler`」——**那是错的**：
 * 本仓的 {@code JobRegistry} 只是注册表，<b>它不会自己 tick</b>。现在删掉那个进程内定时，
 * outbox 在生产上就再也没有任何东西驱动了，而且**不会报错** ——
 * 事件静静地堆在表里，直到有人发现某个下游一直没收到消息。
 *
 * <p>所以顺序是：接真调度器（S5 / v4/07 的 J1–J4）→ 在调度器上启用本任务 →
 * 确认跑起来 → 再删 {@code OutboxDispatchScheduler} 并让台账变短。
 *
 * <p>在那之前两者并存是安全的：投递语义本来就是**至少一次**，
 * 消费端有 {@code EventIdempotency} 兜底（见 {@code OutboxDispatchScheduler} 的类注释）。
 */
@Component
public class OutboxDispatchJob implements JobHandler {

    private final OutboxDispatcher dispatcher;

    public OutboxDispatchJob(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() {
        return "outbox-dispatch";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = dispatcher.dispatchDue();
        return n == 0 ? JobResult.skipped("没有待投递的事件") : JobResult.success("dispatched=" + n);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration outboxDispatchDeclaration() {
            return JobDeclaration.of("outbox-dispatch", "事件投递", "*/5 * * * * *")
                    .ownerModule("基础设施")
                    // 5 秒一轮的高频任务：每次都记日志会把 job_log 淹掉
                    .logEveryRun(false)
                    .timeoutSec(20).lockAtMostSec(30)
                    .build();
        }
    }
}
