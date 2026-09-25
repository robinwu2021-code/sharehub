package ai.neargo.sharehub.loc;

/**
 * 提前终止申请的状态（V104，裁决 #4：合同终止需审批）。
 * 这不是合同状态 —— 审批期间合同照常 ACTIVE（不影响营业），获批后到终止日才迁到 TERMINATED。
 */
public enum ContractTermReqStatus {
    PENDING, APPROVED, REJECTED
}
