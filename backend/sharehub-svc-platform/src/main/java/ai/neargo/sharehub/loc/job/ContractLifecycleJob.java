package ai.neargo.sharehub.loc.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractTickResult;
import ai.neargo.sharehub.loc.service.ContractService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 合同生效 / 到期 / 终止（S-CTR-03）。每小时跑：已批且签署的合同到生效日即生效；生效中的合同过了到期日即到期；
 * 终止申请已获批且到了终止日的即终止。
 * 「今天」按业务时区取（sharehub.biz-zone）—— 合同期限是业务日期。
 */
@Component
public class ContractLifecycleJob implements JobHandler {

    private final ContractService contracts;

    public ContractLifecycleJob(ContractService contracts) {
        this.contracts = contracts;
    }

    @Override
    public String name() {
        return "contract-lifecycle";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        ContractTickResult r = contracts.tick(contracts.today());
        return r.activated() + r.expired() + r.terminated() == 0
                ? JobResult.skipped("无到期 / 待生效 / 待终止合同")
                : JobResult.success("activated=" + r.activated() + " expired=" + r.expired() + " terminated=" + r.terminated());
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration contractLifecycleDeclaration() {
            return JobDeclaration.of("contract-lifecycle", "合同生效 / 到期 / 终止", "0 5 * * * *")
                    .ownerModule("platform.loc").timeoutSec(120).lockAtMostSec(300).build();
        }
    }
}
