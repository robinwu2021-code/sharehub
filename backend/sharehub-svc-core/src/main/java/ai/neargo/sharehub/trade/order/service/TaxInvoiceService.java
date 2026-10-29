package ai.neargo.sharehub.trade.order.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.order.entity.OrdTaxInvoice;

import java.math.BigDecimal;
import java.util.List;

/**
 * C 端租赁税务发票（运营方原始需求 C01 / RQ-INV，第 3 批，V181）。
 *
 * <p>是否开、何时开、谁来开都是配置（{@code invoice.*} 系统参数 + 税务设置的开票主体）。
 * <b>开票失败绝不影响收款</b>：所有入口自己吞异常，失败记 BLOCKED 并打 ERROR，运营在发票页重试。
 */
public interface TaxInvoiceService {

    /** 一笔收款成功后调用；按 {@code invoice.b2c.trigger} 决定现在开还是等结单。 */
    void onPaid(String orderNo, String payNo, boolean orderSettled);

    /** 已扣款未归还的单归还结单（ON_SETTLE 模式在此补开）。 */
    void onSettled(String orderNo);

    /** 退款批准后调用：按 {@code invoice.credit_note.on_refund} 开贷项通知单冲红。 */
    void onRefundApproved(String orderNo, String refundNo, BigDecimal amount, String currency);

    PageResult<OrdTaxInvoice> page(String orderNo, String kind, String status, int pageNum, int pageSize);

    OrdTaxInvoice get(String invoiceNo);

    /** 运营重试 BLOCKED 的票（补齐开票主体后）。 */
    OrdTaxInvoice retry(String invoiceNo);

    /** C 端：本人某订单的票（只含 ISSUED）。 */
    List<OrdTaxInvoice> listForUser(String cUserNo, String orderNo);
}
