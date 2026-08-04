package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.finance.dto.FinDtos.Invoice;
import ai.neargo.sharehub.finance.dto.FinDtos.InvoiceSaveReq;
import ai.neargo.sharehub.finance.dto.FinDtos.InvoiceView;

/**
 * 发票服务（{@code fin_invoice} + {@code fin_invoice_item}）—— <b>运营侧开票管理</b>。
 *
 * <p>两点不可含糊：
 * <ul>
 *   <li>业务键前缀是 {@code INV}（{@code BizKey.INVOICE_OPS}）；C 端「开票申请」是 {@code UINV}，
 *       另一张表 —— 混用会直接撞号；</li>
 *   <li>关联订单落 {@code fin_invoice_item} 子表，<b>不落 {@code order_nos JSON}</b>（[db-design §1.7]）。</li>
 * </ul>
 */
public interface InvoiceService {

    PageResult<Invoice> page(Integer page, Integer size, String keyword, String status);

    /** 详情 = 主单 + 关联订单号（来自子表）。 */
    InvoiceView detail(String invoiceNo);

    /** upsert：{@code invoiceNo} 为空则取号新建（前缀 {@code INV}），否则更新；订单关联整体重建。 */
    InvoiceView save(InvoiceSaveReq req);

    /**
     * 开具发票：{@code PENDING → ISSUED}，记开票人与时间。
     *
     * <p><b>开票人取登录态，不接受入参</b> —— 与提现审批同一条红线：
     * 信前端传的操作人，留痕就可伪造。
     */
    InvoiceView issue(String invoiceNo);

    /**
     * 作废发票：{@code ISSUED → VOID}，**必须填作废原因**。
     *
     * <p>没有原因的作废等于没有记录 —— 税务稽查时无法解释这张票为什么废了。
     */
    InvoiceView voidInvoice(String invoiceNo, String voidReason);
}
