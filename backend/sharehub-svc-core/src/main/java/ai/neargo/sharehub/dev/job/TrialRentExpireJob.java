package ai.neargo.sharehub.dev.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.dev.service.TrialRentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** 试借还超时（S-DEV-04）：超 15 分钟未完成 → EXPIRED，释放「进行中」占位，运维可以重新发起。 */
@Component
public class TrialRentExpireJob implements JobHandler {

    private final TrialRentService trials;

    public TrialRentExpireJob(TrialRentService trials) {
        this.trials = trials;
    }

    @Override
    public String name() {
        return "trial-rent-expire";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = trials.expireStale();
        return n == 0 ? JobResult.skipped("无超时试借还") : JobResult.success("expired=" + n);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration trialRentExpireDeclaration() {
            return JobDeclaration.of("trial-rent-expire", "试借还超时", "0 */5 * * * *")
                    .ownerModule("core.dev").timeoutSec(60).lockAtMostSec(240).build();
        }
    }
}
