package ai.neargo.sharehub.inv.remote;

import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.inv.port.InventoryEngineRouter;
import ai.neargo.sharehub.inv.service.InventoryOperationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 每 5 分钟把待复核的库存操作向库存服务查回一遍（对接明细 F05）：结果不明的步骤过了在途窗口，
 * 查到记完成、查不到记失败。只定结论，不替人重做 —— 远端已执行、本地没完成的，仍由原操作人原样重试。
 * 手动：{@code POST /internal/job/inventory-operation-resolve/trigger}。
 */
@Component
public class InventoryOperationResolveJob implements JobHandler {

    static final String NAME = "inventory-operation-resolve";

    private final InventoryOperationService operations;
    private final RemoteInventoryPort remote;
    private final SysParamPort params;

    public InventoryOperationResolveJob(InventoryOperationService operations, RemoteInventoryPort remote, SysParamPort params) {
        this.operations = operations;
        this.remote = remote;
        this.params = params;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        if (!InventoryEngineRouter.isService(params) || !remote.api().configured()) return JobResult.skipped("引擎不是 SERVICE 或库存服务未配置");
        int n = operations.resolveAll();
        return n == 0 ? JobResult.skipped("没有待查回的库存操作") : JobResult.success("resolved=" + n);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration inventoryOperationResolveDeclaration() {
            return JobDeclaration.of(NAME, "查回结果不明的库存操作", "0 */5 * * * *")
                    .ownerModule("inv.remote").timeoutSec(240).lockAtMostSec(270).build();
        }
    }
}
