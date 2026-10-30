package ai.neargo.sharehub.inv;

/** 跟踪方式（{@code inv_material.tracking_mode}，V210）：同 ai-shop 物料 trackingMode，有流水后不可改。 */
public enum MaterialTracking {
    /** 逐件（序列号）。 */
    SERIAL,
    /** 按数量。 */
    QUANTITY
}
