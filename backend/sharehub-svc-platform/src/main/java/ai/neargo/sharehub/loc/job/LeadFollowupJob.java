package ai.neargo.sharehub.loc.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadTickResult;
import ai.neargo.sharehub.loc.ext.service.LeadOpsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 商机跟进（对齐清单 B7 · B9）。每天 09:00：超 N 天无跟进提醒负责人、超 M 天回收到公共线索池、
 * 竞品独家到期前 K 天重新激活丢单商机。N / M / K 在系统参数里（lead.*）。
 */
@Component
public class LeadFollowupJob implements JobHandler {

    private final LeadOpsService leads;

    public LeadFollowupJob(LeadOpsService leads) {
        this.leads = leads;
    }

    @Override
    public String name() {
        return "lead-followup";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        LeadTickResult r = leads.tick(LocalDateTime.now());
        return r.reminded() + r.pooled() + r.reactivated() == 0
                ? JobResult.skipped("无需提醒 / 回收 / 激活的商机")
                : JobResult.success("reminded=" + r.reminded() + " pooled=" + r.pooled() + " reactivated=" + r.reactivated());
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration leadFollowupDeclaration() {
            return JobDeclaration.of("lead-followup", "商机跟进提醒 / 回收 / 重新激活", "0 0 9 * * *")
                    .ownerModule("platform.loc").timeoutSec(120).lockAtMostSec(300).build();
        }
    }
}
