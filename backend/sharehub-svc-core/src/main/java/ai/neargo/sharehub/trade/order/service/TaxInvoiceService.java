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

    /** 已开出（可下载、可冲红）；另一个状态 BLOCKED 见实现类。 */
    String ISSUED = "ISSUED";

    /** 一笔收款成功后调用；按 {@code invoice.b2c.trigger} 决定现在开还是等结单。 */
    void onPaid(String orderNo, String payNo, boolean orderSettled);

    /** 已扣款未归还的单归还结单（ON_SETTLE 模式在此补开）。 */
    void onSettled(String orderNo);

    /** 退款批准后调用：按 {@code invoice.credit_note.on_refund} 开贷项通知单冲红。 */
    void onRefundApproved(String orderNo, String refundNo, BigDecimal amount, String currency);

    PageResult<OrdTaxInvoice> page(String orderNo, String kind, String status, int pageNum, int pageSize);

    OrdTaxInvoice get(String invoiceNo);

    /**
     * 导出用（K8 / RQ-INV-07~08）：同列表筛选再加开票日期区间（业务日，含两端），按票号升序，最多 {@code limit} 张。
     * 日期区间按 {@code created_at} 落：BLOCKED 的票没有开出时间，但登记表里也要看到它。
     */
    List<OrdTaxInvoice> listForExport(String orderNo, String kind, String status,
                                      java.time.LocalDate from, java.time.LocalDate to, int limit);

    /** 运营重试 BLOCKED 的票（补齐开票主体后）。 */
    OrdTaxInvoice retry(String invoiceNo);

    /** C 端：本人某订单的票（只含 ISSUED）。 */
    List<OrdTaxInvoice> listForUser(String cUserNo, String orderNo);
}
