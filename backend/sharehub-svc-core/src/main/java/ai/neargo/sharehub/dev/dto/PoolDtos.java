package ai.neargo.sharehub.dev.dto;

import java.util.List;

/** 出库池入参（V161）。 */
/**
 * 写操作都可带 requestKey（对接明细 §9.3）：页面每次打开表单生成一个，提交与重试都带同一个 ——
 * 库存服务已执行但响应丢了时，原样重试按同一键续做，不会重复出库 / 领取。不带则只在本次请求内可恢复。
 */
public final class PoolDtos {

    private PoolDtos() {
    }

    /**
     * 出库给商家：整批一个事务。siteNo 可空（到商家）；receiverNo 可空 = 出库即签收（经办人当面交付），
     * 填了则须由该接收人签收后才能绑定（V195）。carrier / trackingNo 可选：寄送时的物流（V205）。
     */
    public record IssueReq(String agentNo, String siteNo, String receiverNo, List<String> cabinetNos,
                           String carrier, String trackingNo, String requestKey) {
    }

    /** 接收人签收：只能签给自己的。 */
    public record ClaimReq(List<String> cabinetNos, String requestKey) {
    }

    /** 出库调整（出错时改商家 / 站点 / 接收人）：只收未绑定的；原因必填。改了接收人要重新签收。 */
    public record RedirectReq(List<String> cabinetNos, String agentNo, String siteNo, String receiverNo, String reason, String requestKey) {
    }

    /**
     * 出库撤销（对接明细 C13）：只收未绑定的；原因必填。撤销回原出库仓 —— warehouseNo 可空，填了须是原出库仓；
     * 实物退到别的仓走退仓。
     */
    public record PoolReturnReq(List<String> cabinetNos, String warehouseNo, String reason, String requestKey) {
    }
}
