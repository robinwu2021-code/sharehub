package ai.neargo.sharehub.inv;

/**
 * 调拨单状态（{@code inv_transfer.status}，[db-design §3.3]）—— {@link InvTransferStateMachine} 的取值域。
 *
 * <p>本枚举 2026-09-25 从状态机里的三个 {@code String} 常量提升而来。常量已经比裸串好，
 * 但它<b>挡不住外面传进来的任意字符串</b>：{@code next("DRAFT ", "SHIP")} 会当成非法迁移抛错，
 * 而调用方看到的是「非法迁移」而不是「你的状态值有空格」。枚举把这一类错前移到解析处。
 *
 * <p>同时它让运营端的 {@code InvTransferStatus} 进入 {@code StatusVocabularyAcrossEndsTest}
 * 的覆盖 —— 此前前端那份是<b>内联</b>在 interface 里的联合（{@code status: "DRAFT" | …}），
 * 解析器只认具名 {@code export type}，于是整个调拨域的词表两端从未被比对过。
 */
public enum InvTransferStatus {

    /** 草稿：单据可改，未发出。 */
    DRAFT,
    /** 在途：已发出，未收货。 */
    IN_TRANSIT,
    /**
     * 已收货 —— <b>终态</b>。
     *
     * <p>「已收货的单能不能退回在途」的答案是不能：要退货应开一张反向调拨单，
     * 留两条痕，而不是把一条痕改回去（见 {@link InvTransferStateMachine} 类注释）。
     */
    DONE;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static InvTransferStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("调拨单状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("调拨单状态非法: " + v + "（仅 DRAFT/IN_TRANSIT/DONE）");
        }
    }
}
