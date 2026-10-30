package ai.neargo.sharehub.inv.remote;

/** 库存事件收件箱状态（{@code inventory_inbox.status}，V198）。 */
public enum InboxStatus {
    /** 已处理（含只入箱留痕的事件）。 */
    DONE,
    /** 投影失败，待人工核对。 */
    FAILED
}
