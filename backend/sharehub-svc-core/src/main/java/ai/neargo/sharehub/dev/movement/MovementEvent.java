package ai.neargo.sharehub.dev.movement;

/** 设备履历事件（{@code dev_movement_log.event}，V196）：每一种位置或归属变化。 */
public enum MovementEvent {
    /** 出库给商家（可到站点）。 */
    ISSUE,
    /** 接收人签收。 */
    CLAIM,
    /** 出库调整：改商家 / 站点 / 接收人。 */
    REDIRECT,
    /** 出库池退回仓库。 */
    POOL_RETURN,
    /** 绑定点位（含装机扫码改绑）。 */
    BIND,
    /** 解绑回出库池（直营的回仓）。 */
    UNBIND,
    /** 调拨发货：离开仓库进在途。 */
    SHIP,
    /** 调拨签收：到仓或到站点。 */
    RECEIVE,
    /** 撤机：离开点位与站点。 */
    UNDEPLOY,
    /** 撤机回仓签收：进到仓待检，不进仓。 */
    RETURN_INTAKE,
    /** 待检合格入库。 */
    INBOUND,
    /** 整站 / 单台换商家。 */
    REASSIGN,
    /** 报废。 */
    RETIRE,
    /** 确认遗失。 */
    LOST
}
