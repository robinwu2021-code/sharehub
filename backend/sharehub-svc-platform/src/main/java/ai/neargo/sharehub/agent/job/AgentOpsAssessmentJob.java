package ai.neargo.sharehub.agent.job;

import ai.neargo.sharehub.agent.ext.service.AgentOpsAssessmentService;
import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/** 代理运维月度考核（对齐清单 F5）：每月 1 日 02:30 考核上一月，结果决定本月的运维分成系数。 */
@Component
public class AgentOpsAssessmentJob implements JobHandler {

    private final AgentOpsAssessmentService assessments;

    public AgentOpsAssessmentJob(AgentOpsAssessmentService assessments) {
        this.assessments = assessments;
    }

    @Override
    public String name() {
        return "agent-ops-assessment";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int n = assessments.assess(YearMonth.now().minusMonths(1).toString());
        return n == 0 ? JobResult.skipped("没有代理") : JobResult.success("assessed=" + n);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration agentOpsAssessmentDeclaration() {
            return JobDeclaration.of("agent-ops-assessment", "代理运维月度考核", "0 30 2 1 * *")
                    .ownerModule("platform.agent").timeoutSec(600).lockAtMostSec(1200).build();
        }
    }
}
