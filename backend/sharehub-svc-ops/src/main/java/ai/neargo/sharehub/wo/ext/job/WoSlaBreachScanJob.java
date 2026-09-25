package ai.neargo.sharehub.wo.ext.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 扫出已超时却还没被标记的工单。
 *
 * <h2>为什么任务名不是 v4/07 §5.1 的 {@code wo-sla-escalate}</h2>
 * 那一条写的是「SLA 超时升级、通知」，而本任务**只做超时判定，不做升级**——
 * 叫 escalate 会让运营端上出现一个名字承诺了却没做的事。
 *
 * <p>升级那一半缺的是**通道**：{@code wo_sla_rule.escalate_to} 指向 role_no/employee_no，
 * 但 {@code sharehub-svc-ops} 今天不依赖通知模块，发不出任何东西。
 * 只把 {@code escalated_at} 写上而不通知任何人，就又是一个「界面有、不生效」——
 * 本轮一直在修的正是这一类。等有了通知端口再补，届时任务可以改名或新增一个。
 *
 * <h2>它补的是事件驱动那一半的盲区</h2>
 * {@code markBreached} 在接单/关单那一刻判。于是**一张彻底躺着没人管的单**
 * 因为永远不会发生那两个事件，超时标记就永远是 0 ——
 * 超时最严重的那些，恰恰是唯一不会被判定的。
 */
@Component
public class WoSlaBreachScanJob implements JobHandler {

    private final WoOpsService woOps;

    public WoSlaBreachScanJob(WoOpsService woOps) {
        this.woOps = woOps;
    }

    @Override
    public String name() {
        return "wo-sla-breach-scan";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = woOps.sweepSlaBreaches();
        return n == 0 ? JobResult.skipped("没有新超时的工单") : JobResult.success("breached=" + n);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration woSlaBreachScanDeclaration() {
            return JobDeclaration.of("wo-sla-breach-scan", "SLA 超时扫描", "0 */5 * * * *")
                    .ownerModule("ops.wo")
                    .logEveryRun(false)
                    .timeoutSec(60).lockAtMostSec(120)
                    .build();
        }
    }
}
