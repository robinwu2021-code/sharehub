package ai.neargo.sharehub.dev.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.dev.port.OwnershipReconciler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 重投挂起的归属变更事件（ADR-019 步 5）。
 *
 * <p>{@link OwnershipReconciler#redeliverPending()} 早就写好了，
 * 但在此之前**没有任何调用方** —— 方法在那儿，谁也不会调它，
 * 于是「站点换了归属、设备归属没跟上」这件事没有任何兜底。
 * 这是本轮 A 类六个任务里唯一一个「写了完全没接线」的。
 */
@Component
public class OwnershipReconcileJob implements JobHandler {

    private final OwnershipReconciler reconciler;

    public OwnershipReconcileJob(OwnershipReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public String name() {
        return "ownership-reconcile";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = reconciler.redeliverPending();
        return n == 0 ? JobResult.skipped("没有挂起的归属事件") : JobResult.success("redelivered=" + n);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration ownershipReconcileDeclaration() {
            return JobDeclaration.of("ownership-reconcile", "设备归属重投", "0 10 3 * * *")
                    .ownerModule("agent")
                    .logEveryRun(true)
                    .timeoutSec(60).lockAtMostSec(120)
                    .build();
        }
    }
}
