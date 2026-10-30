package ai.neargo.sharehub.dev.dto;

import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;

import java.util.List;

/** 绑定 / 解绑（V164，技术设计 §3.4）。 */
public final class BindDtos {

    private BindDtos() {
    }

    /**
     * @param locationNo    缺省 = 出库池商家默认站点的默认点位
     * @param openInstallWo 同事务开装机单
     * @param expectedAt    装机单期望完成时间
     */
    public record BindReq(String locationNo, Boolean openInstallWo, String expectedAt) {
    }

    /** 批量绑定：同一商家的一批出库池设备绑到同一个点位（缺省默认点位），一个事务。 */
    public record BindBatchReq(String agentNo, String locationNo, List<String> deviceNos, Boolean openInstallWo,
                               String expectedAt) {
    }

    public record BindResult(Cabinet cabinet, String woNo) {
    }

    public record UnbindReq(String reason) {
    }
}
