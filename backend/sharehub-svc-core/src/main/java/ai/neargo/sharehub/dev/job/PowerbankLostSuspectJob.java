package ai.neargo.sharehub.dev.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.dev.service.PowerbankLossService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** 充电宝疑似丢失（V113）：每天 04:30 标记失联满 N 天的宝、清掉已不成立的、升级久未处理的。不自动转 LOST。 */
@Component
public class PowerbankLostSuspectJob implements JobHandler {

    private final PowerbankLossService loss;

    public PowerbankLostSuspectJob(PowerbankLossService loss) {
        this.loss = loss;
    }

    @Override
    public String name() {
        return "powerbank-lost-suspect";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        PowerbankLossService.ScanResult r = loss.scan();
        return r.marked() + r.cleared() + r.escalated() == 0
                ? JobResult.skipped("没有新的疑似丢失")
                : JobResult.success("marked=" + r.marked() + " cleared=" + r.cleared() + " escalated=" + r.escalated());
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration powerbankLostSuspectDeclaration() {
            return JobDeclaration.of("powerbank-lost-suspect", "充电宝疑似丢失标记", "0 30 4 * * *")
                    .ownerModule("core.dev").timeoutSec(120).lockAtMostSec(300).build();
        }
    }
}
