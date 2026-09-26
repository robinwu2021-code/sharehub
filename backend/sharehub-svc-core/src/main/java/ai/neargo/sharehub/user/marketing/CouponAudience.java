package ai.neargo.sharehub.user.marketing;

/**
 * 发券人群类型（{@code usr_coupon_issue.target_type}，词表见 V27 的列注释 {@code ALL/SEGMENT/USER_LIST}）。
 *
 * <p>建这个 enum 不是为了"好看"：三个值此前只以裸字符串存在于
 * {@code UserCouponServiceImpl} 里，而 {@code USER_LIST} 同时是**判断分支**和**落库值}——
 * 两处各写一遍，写错一个字母就是「校验放过了、库里落了个没人认识的类型」，编译器无从分辨
 * （见 {@code BareStatusLiteralRatchetTest} 类注释里的告警 {@code ACK} vs {@code ACKED}）。
 *
 * <p>值仍以 {@code String} 落库（实体字段保持 String），这里只收字面量的**来源**。
 */
public enum CouponAudience {

    /** 全体用户 —— 后端暂不支持（没有"全体"的解析口径：一万人发五百张是抽签还是先到先得？）。 */
    ALL,

    /** 人群包 —— 后端暂不支持（{@code mkt_segment} 表都还没有）。 */
    SEGMENT,

    /** 指定用户号列表，逗号分隔。目前**唯一**受理的类型。 */
    USER_LIST;

    /** 宽松解析：空/空白按 {@link #ALL}；不认识的值返回 {@code null}，由调用方决定怎么拒。 */
    public static CouponAudience parse(String raw) {
        if (raw == null || raw.isBlank()) return ALL;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
