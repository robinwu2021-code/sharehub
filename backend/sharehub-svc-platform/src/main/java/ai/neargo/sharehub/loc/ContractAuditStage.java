package ai.neargo.sharehub.loc;

/** 待审批合同所在的审批环节（V104）：先运营，超阈值的再财务会签。仅 PENDING 时有值。 */
public enum ContractAuditStage {
    OPS, FINANCE
}
