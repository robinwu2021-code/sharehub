package ai.neargo.sharehub.user.core.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.InvoiceItem;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.InvoiceTitleItem;
import ai.neargo.sharehub.user.core.entity.UsrInvoice;
import ai.neargo.sharehub.user.core.entity.UsrInvoiceTitle;

/**
 * C 端抬头与开票（usr_invoice_title / usr_invoice，C-IV-01/02/03）。属主过滤。
 *
 * <p><b>业务键前缀 {@code UINV}</b>（不是运营侧的 {@code INV}）——
 * 两侧发票是两张表，同前缀会撞业务键，且历史数据改不回来。
 */
public interface UserInvoiceService {

    // —— 抬头 ——

    PageResult<InvoiceTitleItem> pageTitles(String cUserNo, Integer page, Integer size);

    /**
     * 新增/修改抬头（upsert，按 {@code titleNo}）。
     * {@code type=COMPANY} 时 {@code vatTrn} 必填；设为默认时自动把该用户其它抬头降级。
     */
    InvoiceTitleItem saveTitle(String cUserNo, UsrInvoiceTitle body);

    // —— 开票申请 ——

    PageResult<InvoiceItem> pageInvoices(String cUserNo, Integer page, Integer size, String status);

    /**
     * 提交开票申请。落 {@code status=APPLIED}，抬头名做快照。
     *
     * @throws IllegalArgumentException 抬头不存在或不属于该用户
     */
    InvoiceItem apply(String cUserNo, UsrInvoice body);
}
