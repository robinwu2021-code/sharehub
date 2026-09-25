package ai.neargo.sharehub.inv;

/** 资产差异种类（{@code inv_asset_diff.kind}，V107）。 */
public enum AssetDiffKind {
    /** 调拨单上有、签收时没收到。 */
    MISSING,
    /** 签收时收到了、单上没有。 */
    EXTRA,
    /** 撤机清点数与系统在柜数不符。 */
    COUNT_MISMATCH
}
