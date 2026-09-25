package ai.neargo.sharehub.inv;

/** 资产差异处理状态（{@code inv_asset_diff.status}）。处理 = 查清去向并写明结论，不改差异本身。 */
public enum AssetDiffStatus {
    OPEN, RESOLVED
}
