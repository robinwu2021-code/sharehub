package ai.neargo.sharehub.user.core;

import ai.neargo.sharehub.common.BizException;

/**
 * 发票抬头类型（{@code usr_invoice_title.type}，DDL 注释 {@code 'PERSONAL/COMPANY'}）。
 *
 * <p>{@link #COMPANY} 时 {@code vat_trn}（税号）必填 —— 这条判断
 * {@code UserInvoiceServiceImpl} 里原先写成字面量 {@code "COMPANY".equals(...)}，
 * 而 type 本身不校验：传 {@code company}（小写）就绕过了税号必填，
 * <b>那张发票开出去是废的</b>。所以这里连大小写一起归一。
 */
public enum InvoiceTitleType {

    /** 个人抬头。 */
    PERSONAL,
    /** 企业抬头：税号必填。 */
    COMPANY;

    /** 宽松解析：非法值抛 {@link BizException}（400，文案走 i18n key —— 中文字面量会原样送到英文 / 阿语界面）。 */
    public static InvoiceTitleType of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "titleType");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.common.invalid_value", v + " (PERSONAL/COMPANY)");
        }
    }
}
