package ai.neargo.sharehub.dev.dto;

import java.util.List;

/** 出库池入参（V161）。 */
public final class PoolDtos {

    private PoolDtos() {
    }

    /**
     * 出库给商家：整批一个事务。siteNo 可空（到商家）；receiverNo 可空 = 出库即签收（经办人当面交付），
     * 填了则须由该接收人签收后才能绑定（V195）。
     */
    public record IssueReq(String agentNo, String siteNo, String receiverNo, List<String> cabinetNos) {
    }

    /** 接收人签收：只能签给自己的。 */
    public record ClaimReq(List<String> cabinetNos) {
    }

    /** 出库调整（出错时改商家 / 站点 / 接收人）：只收未绑定的；原因必填。改了接收人要重新签收。 */
    public record RedirectReq(List<String> cabinetNos, String agentNo, String siteNo, String receiverNo, String reason) {
    }

    /** 出库池退回仓库：只收未绑定的；原因必填。 */
    public record PoolReturnReq(List<String> cabinetNos, String warehouseNo, String reason) {
    }
}
