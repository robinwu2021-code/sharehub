package ai.neargo.sharehub.inv.dto;

/** 安全库存（V204）。 */
public final class ThresholdDtos {
    private ThresholdDtos() {
    }

    /** 一行 = 一个启用中的仓 × 一种设备；未设下限的 minQty 为 0。low = minQty &gt; 0 且可用量低于它。 */
    public record StockThreshold(String warehouseNo, String warehouseName, String itemType, int minQty, int available, boolean low) {
    }

    public record StockThresholdReq(String warehouseNo, String itemType, Integer minQty) {
    }
}
