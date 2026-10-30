package ai.neargo.sharehub.inv.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 库存执行异常（对接明细 F04 / F05）：未完成的库存写操作与它的远端步骤。不含请求原文。 */
public final class OperationDtos {

    private OperationDtos() {
    }

    /**
     * 一个库存写操作。phase：PREPARED 进行中 · NEEDS_REVIEW 待复核（有步骤结果不明或远端已执行、本地没完成）·
     * REJECTED 发请求前被拒或库存服务明确拒绝 · COMPLETED 已完成。retryable：当前用户能原样重试（原操作人、仍有权限）。
     */
    public record Operation(String operationNo, String kind, String bizRef, String phase, int attempts, String lastError,
                            String createdBy, LocalDateTime createdAt, LocalDateTime updatedAt, List<Step> steps, boolean retryable) {
    }

    /** status：UNKNOWN 结果不明 · COMPLETED 远端已执行 · FAILED 远端明确没执行。 */
    public record Step(String code, String status, String remoteRef, String lastError, LocalDateTime updatedAt) {
    }
}
