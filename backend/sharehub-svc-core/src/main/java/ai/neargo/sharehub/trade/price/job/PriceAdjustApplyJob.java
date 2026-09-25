package ai.neargo.sharehub.trade.price.job;

import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.trade.price.service.PriceAdjustmentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 预约调价到点生效 / 到期恢复（v4/07 §5.1 的 {@code price-adjust-apply}）。
 *
 * <p>业务全在 {@link PriceAdjustmentService#tick()} 里，本类只是壳。
 * {@code tick()} 幂等（按状态条件更新），生效与恢复在同一次里都可能发生 ——
 * 服务停了一段时间之后补算时正需要这个。
 *
 * <p>注意 {@code tick()} 今天还有两个调用方：内部端点
 * {@code POST /internal/trade/price-adjustments/tick}，以及列表页 {@code page()} 里的
 * 惰性调用（「不必等调度器那一分钟」）。**三者并存无害**，因为它幂等；
 * 接上调度器后也不必去掉惰性那处 —— 它保证的是打开页面看到的状态是最新的。
 */
@Component
public class PriceAdjustApplyJob implements JobHandler {

    private final PriceAdjustmentService adjustments;

    public PriceAdjustApplyJob(PriceAdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @Override
    public String name() {
        return "price-adjust-apply";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        List<String> changed = adjustments.tick();
        return changed.isEmpty()
                ? JobResult.skipped("没有到点的调价单")
                : JobResult.success("changed=" + changed.size() + " " + changed);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration priceAdjustApplyDeclaration() {
            return JobDeclaration.of("price-adjust-apply", "预约调价生效", "30 * * * * *")
                    .ownerModule("trade.price")
                    .logEveryRun(false)
                    .timeoutSec(30).lockAtMostSec(60)
                    .build();
        }
    }
}
