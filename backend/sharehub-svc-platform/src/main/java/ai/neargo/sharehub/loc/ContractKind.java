package ai.neargo.sharehub.loc;

/** 合同种类（V104）。补充协议关联原合同，生效日起取代原条款；原合同置 EXPIRED 保留，历史订单不追溯。 */
public enum ContractKind {
    MAIN, SUPPLEMENT
}
