package ai.neargo.sharehub.finance;

/**
 * 发票状态（{@code fin_invoice.status}）。
 *
 * <p>{@link #VOID} 是**终态**：作废后不能再开、不能再作废 —— {@code InvoiceServiceImpl}
 * 三处都先判它。发票关联税务，重开必须另开一张，不能把作废的改回去。
 */
public enum InvoiceStatus {

    /** 草稿（DDL 默认值）。 */
    DRAFT,
    /** 已开具。 */
    ISSUED,
    /** 已作废，**终态**。 */
    VOID;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static InvoiceStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("发票状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("发票状态非法: " + v + "（仅 DRAFT/ISSUED/VOID）");
        }
    }
}
