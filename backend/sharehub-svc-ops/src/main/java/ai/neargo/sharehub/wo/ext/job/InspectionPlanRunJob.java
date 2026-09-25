package ai.neargo.sharehub.wo.ext.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.wo.ext.service.InspectionPlanService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 当天到期的巡检计划生成工单。
 *
 * <p>批量扫在 {@link InspectionPlanService#runDue()} 里（业务留在 service，handler 只是壳）。
 * 已有的 {@code POST /inspection-plans/{planNo}/run} 是**按单个计划**的手动触发，保留不动。
 *
 * <p>不用 {@code nextAt} 做扫描条件：那一列注释写的是「由调度器回写」，而至今没人写它，
 * 拿它当条件会永远扫不出东西。改为遍历启用的计划逐个调 ——
 * {@code run()} 自带周期幂等键，本周期跑过的会自己拒。
 */
@Component
public class InspectionPlanRunJob implements JobHandler {

    private final InspectionPlanService plans;

    public InspectionPlanRunJob(InspectionPlanService plans) {
        this.plans = plans;
    }

    @Override
    public String name() {
        return "inspection-plan-run";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        List<String> ran = plans.runDue();
        return ran.isEmpty()
                ? JobResult.skipped("没有到期的巡检计划")
                : JobResult.success("plans=" + ran.size() + " " + ran);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration inspectionPlanRunDeclaration() {
            return JobDeclaration.of("inspection-plan-run", "巡检计划生成工单", "0 0 6 * * *")
                    .ownerModule("ops.wo")
                    .logEveryRun(true)
                    .timeoutSec(120).lockAtMostSec(300)
                    .build();
        }
    }
}
