package ai.neargo.sharehub.loc.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.loc.service.SiteService;
import ai.neargo.sharehub.loc.service.SiteService.SiteTickResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 站点上线对账（S-SITE-02 兜底）。首台设备上线靠 {@code CabinetWentLiveEvent} 驱动站点转营业；
 * Outbox 进程内重投修好之前事件可能丢，丢了站点就一直 PREPARING、借不了 —— 每小时扫一遍补上。
 */
@Component
public class SiteGoLiveReconcileJob implements JobHandler {

    private final SiteService sites;

    public SiteGoLiveReconcileJob(SiteService sites) {
        this.sites = sites;
    }

    @Override
    public String name() {
        return "site-golive-reconcile";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        SiteTickResult r = sites.tick();
        return r.wentLive() + r.pauseOverdue() + r.autoClosed() == 0
                ? JobResult.skipped("无待对账站点")
                : JobResult.success("wentLive=" + r.wentLive() + " pauseOverdue=" + r.pauseOverdue() + " autoClosed=" + r.autoClosed());
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration siteGoLiveReconcileDeclaration() {
            return JobDeclaration.of("site-golive-reconcile", "站点上线对账", "0 10 * * * *")
                    .ownerModule("platform.loc").timeoutSec(120).lockAtMostSec(300).build();
        }
    }
}
