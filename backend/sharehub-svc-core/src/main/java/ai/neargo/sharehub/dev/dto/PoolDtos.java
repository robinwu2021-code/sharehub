package ai.neargo.sharehub.dev.dto;

import java.util.List;

/** 出库池入参（V161）。 */
public final class PoolDtos {

    private PoolDtos() {
    }

    /** 出库给商家：整批一个事务。 */
    public record IssueReq(String agentNo, List<String> cabinetNos) {
    }

    /** 出库池退回仓库：只收未绑定的；原因必填。 */
    public record PoolReturnReq(List<String> cabinetNos, String warehouseNo, String reason) {
    }
}
