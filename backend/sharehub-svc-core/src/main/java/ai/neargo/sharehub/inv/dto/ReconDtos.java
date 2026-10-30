package ai.neargo.sharehub.inv.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 库存对账（对接明细 F07）：本地业务 vs 库存服务，只报差异，不改任何一边。 */
public final class ReconDtos {

    private ReconDtos() {
    }

    /**
     * 一条差异。kind：MISSING_REMOTE 本地在仓、库存服务没有 · WRONG_LOCATION 两边仓不同 · EXTRA_REMOTE 库存服务在仓、本地不认 ·
     * POOL_MISSING 本地已出库、库存服务出库池没有 · POOL_EXTRA 出库池有、本地未出库 · POOL_MERCHANT 出库商家不同 ·
     * CLAIM_STATE 签收状态不同。
     */
    public record Diff(String kind, String itemType, String itemNo, String local, String remote, String hint) {
    }

    /** diffs 最多 500 条，total 为全量差异数。 */
    public record Report(LocalDateTime asOf, int inWarehouseChecked, int poolChecked, int total, List<Diff> diffs) {
    }
}
