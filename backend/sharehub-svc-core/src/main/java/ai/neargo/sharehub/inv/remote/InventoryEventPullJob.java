package ai.neargo.sharehub.inv.remote;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** 每分钟拉一次库存事件（S3.2）。引擎为 LOCAL 时空跑跳过。手动：{@code POST /internal/job/inventory-event-pull/trigger}。 */
@Component
public class InventoryEventPullJob implements JobHandler {

    static final String NAME = "inventory-event-pull";

    private final InventoryEventPuller puller;

    public InventoryEventPullJob(InventoryEventPuller puller) {
        this.puller = puller;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        InventoryEventPuller.Result r = puller.pullOnce();
        if (r.pulled() == 0) return JobResult.skipped("没有新事件（或引擎不是 SERVICE）");
        return JobResult.success("pulled=" + r.pulled() + " processed=" + r.processed() + " dup=" + r.duplicates() + " failed=" + r.failed());
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration inventoryEventPullDeclaration() {
            return JobDeclaration.of(NAME, "拉取独立进销存事件并投影", "0 * * * * *")
                    .ownerModule("inv.remote").timeoutSec(50).lockAtMostSec(55).build();
        }
    }
}
