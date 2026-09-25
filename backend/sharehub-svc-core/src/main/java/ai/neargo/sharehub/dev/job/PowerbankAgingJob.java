package ai.neargo.sharehub.dev.job;

import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.dev.port.PowerbankTracker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** 充电宝老化标记（对齐清单 D2）：每天 04:10，循环次数超过 {@code device.powerbank.retire_cycles}（默认 500）的宝标 AGED、停止借出。 */
@Component
public class PowerbankAgingJob implements JobHandler {

    private final PowerbankTracker tracker;
    private final SysParamPort params;

    public PowerbankAgingJob(PowerbankTracker tracker, SysParamPort params) {
        this.tracker = tracker;
        this.params = params;
    }

    @Override
    public String name() {
        return "powerbank-aging";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = tracker.markAged(params.intOf("device.powerbank.retire_cycles", 500));
        return n == 0 ? JobResult.skipped("没有新的老化宝") : JobResult.success("aged=" + n);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration powerbankAgingDeclaration() {
            return JobDeclaration.of("powerbank-aging", "充电宝老化标记", "0 10 4 * * *")
                    .ownerModule("core.dev").timeoutSec(120).lockAtMostSec(300).build();
        }
    }
}
