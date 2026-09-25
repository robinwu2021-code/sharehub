package ai.neargo.sharehub.user.core.service;

import ai.neargo.sharehub.user.core.dto.UserCoreDtos;
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
    InvoiceItem apply(String cUserNo, UserCoreDtos.InvoiceApplyReq req);

    // —— 运营端受理（消费者提交之后总得有人处理）——

    /**
     * 运营端开票申请队列（{@code GET /api/user/cuser-invoices}）。
     *
     * <p>运营端「发票」那个菜单叶管的是 {@code fin_invoice} —— 给场地方/代理商开的**结算发票**，
     * 与这里的消费者开票是两个对象、两张表、两套业务键。
     * 于是 C 端能提交开票申请，而**提交之后无人受理**，申请永远停在 APPLIED。
     */
    ai.neargo.common.core.PageResult<UserCoreDtos.CUserInvoiceRow> pageForOps(
            Integer page, Integer size, String keyword, String status);

    /**
     * 开具：置 {@code ISSUED} 并回填发票文件地址与受理人。
     *
     * @throws ai.neargo.common.core.ServerException 非 APPLIED 态（409）—— 已开具/已驳回的不再受理
     */
    UserCoreDtos.CUserInvoiceRow issue(String invoiceNo, String fileUrl, String operator);

    /**
     * 驳回：置 {@code REJECTED} 并记原因。
     *
     * <p><b>原因必填</b>。只说「已驳回」等于让用户无从改正后重提 —— 而他会做的事是再提一次，
     * 于是队列里多一条一样的单。
     *
     * @throws IllegalArgumentException 原因为空
     * @throws ai.neargo.common.core.ServerException 非 APPLIED 态（409）
     */
    UserCoreDtos.CUserInvoiceRow reject(String invoiceNo, String reason, String operator);
}
