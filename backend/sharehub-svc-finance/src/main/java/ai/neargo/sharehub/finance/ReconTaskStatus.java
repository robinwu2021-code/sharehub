package ai.neargo.sharehub.finance;

/**
 * 对账任务结果（{@code recon_task.status}）。
 *
 * <p>只有两个值是有意的：对账的产出不是"进度"而是**结论** ——
 * 要么三方对平（{@link #MATCHED}），要么有差错待处置（{@link #DIFF}，明细在 {@code recon_diff}）。
 */
public enum ReconTaskStatus {

    /** 通道账单 ↔ 支付单 ↔ 账务分录 三方对平。 */
    MATCHED,
    /** 存在差错，见 {@code recon_diff}。 */
    DIFF;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static ReconTaskStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("对账状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("对账状态非法: " + v + "（仅 MATCHED/DIFF）");
        }
    }
}
