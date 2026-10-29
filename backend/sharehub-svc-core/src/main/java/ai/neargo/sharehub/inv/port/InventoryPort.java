package ai.neargo.sharehub.inv.port;

import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;

import java.util.List;

/**
 * 库存账动作的唯一入口（S2.5，docs/technical/独立进销存/D §2.1 / §2.8）。
 *
 * <p>入库、出库、签收、出库调整、出库池退回 —— 改的是「设备在不在库存账上、在谁名下」，
 * 独立进销存上线后由它记账。调用方（控制器、待检单）只认这个接口：
 * 现在是 {@link LocalInventoryPort}（直接改本地设备表），切到库存服务时换成远程实现，调用方不动。
 *
 * <p>绑定、解绑、撤机回出库池、换站点、整站换商家**不在这里** —— 那些只在业务侧处理（PB-D3）。
 */
public interface InventoryPort {

    /** 当前引擎：LOCAL（本地设备表）/ SERVICE（独立进销存）。 */
    String engine();

    /** 质检合格入库（待检单驱动）：带质检记录号作为放行凭证。 */
    void inbound(String itemType, String itemNo, String warehouseNo, String qcNo, String intakeNo);

    /** 出库到商家（可到站点），可指定内部接收人。 */
    List<Cabinet> dispatch(String agentNo, String siteNo, String receiverNo, List<String> cabinetNos);

    /** 接收人签收。 */
    List<Cabinet> claim(List<String> cabinetNos);

    /** 出库调整：改商家 / 站点 / 接收人。 */
    List<Cabinet> redirect(List<String> cabinetNos, String agentNo, String siteNo, String receiverNo, String reason);

    /** 出库池退回仓库。 */
    List<Cabinet> returnToWarehouse(List<String> cabinetNos, String warehouseNo, String reason);
}
