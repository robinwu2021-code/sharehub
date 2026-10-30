package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.api.common.Checklist;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;

/**
 * 机柜状态动作（TDD-运营核心流程/04 §4.1）。状态只经这里改；编辑接口（CabinetService.save）不再收 status。
 */
public interface CabinetLifecycleService {

    /** 上线门禁（只读）。{@link #goLive} 在事务内重跑一遍。 */
    Checklist goLiveGate(String cabinetNo);

    /** 门禁全过 → DEPLOYED，发 CabinetWentLiveEvent（站点 PREPARING → ACTIVE）。 */
    Cabinet goLive(String cabinetNo);

    Cabinet markFault(String cabinetNo, String reason);

    Cabinet repair(String cabinetNo);

    /** 撤机回仓：DEPLOYED / FAULT → IN_STOCK，解绑点位（站点关闭门禁据此放行）。 */
    Cabinet undeploy(String cabinetNo, String reason);

    /** 内部完工路径绑定撤机工单，供后续回仓验证当前流转。 */
    Cabinet undeploy(String cabinetNo, String reason, String sourceRef);

    /** 调拨发货（C4）：IN_STOCK → IN_TRANSIT。质检未过（PENDING / FAILED）不能发。由调拨单驱动。 */
    void ship(String cabinetNo, String transferNo);

    /** 调拨签收（C4）：IN_TRANSIT → IN_STOCK，回写所在仓。 */
    void receive(String cabinetNo, String warehouseNo);

    /** 撤机回仓签收：IN_TRANSIT → IN_STOCK，离开出库池但不进仓，质检打回待检 —— 由到仓待检单复检后入库。 */
    void receiveForInspection(String cabinetNo);

    /** 装机工单完工后（C5）：在库且门禁全过即自动上线；返回是否上线。不过则保持在库，门禁由人看。 */
    boolean goLiveIfReady(String cabinetNo, String cause);

    Cabinet retire(String cabinetNo, String reason);

    /**
     * 设备页上的报废（对接明细 E11）：只收仓外的设备。在仓的（在库、有仓、未出库、未绑定）必须走处置出库 ——
     * 那条路会在库存服务同步出库；从这里直接报废等于绕过库存账，两边从此对不上。
     */
    Cabinet retireOutOfWarehouse(String cabinetNo, String reason);
}
