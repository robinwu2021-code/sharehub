package ai.neargo.sharehub.inv;

/** 处置出库类型（{@code inv_disposal.kind}，V203）。 */
public enum DisposalKind {

    /** 报废：须给原因码，不能带供应商。 */
    SCRAP,
    /** 退供应商：须指定供应商。 */
    RETURN_SUPPLIER
}
