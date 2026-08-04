package ai.neargo.sharehub.cs.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.cs.dto.CsDtos.CsTicketVO;
import ai.neargo.sharehub.cs.dto.CsDtos.ReportReq;
import ai.neargo.sharehub.cs.dto.CsDtos.ReportResultVO;
import ai.neargo.sharehub.cs.dto.CsDtos.TicketUpdateReq;

/**
 * 报障受理（cs_ticket）—— **有业务规则的聚合根**（状态机 + 两个幂等出口），故手写实现。
 *
 * <p>它是 [api/README §6A.2] 定稿的「C 端诉求唯一受理单」：任何 C 端诉求先落它，
 * 再按 {@code md_problem.suggested_action} 字典分流到工单 / 退款 / 人工会话。
 */
public interface CsTicketService {

    /** 运营端列表。 */
    PageResult<CsTicketVO> page(Integer page, Integer size, String keyword, String status, String channel);

    /** 按业务键单查；不存在返回 {@code null}。 */
    CsTicketVO get(String ticketNo);

    /**
     * 受理 / 更新（{@code POST /api/ops/cs/tickets/{ticketNo}}）。
     * 状态只允许 OPEN → PROCESSING → CLOSED 单向前进，非法迁移抛异常（由全局处理器映射为 409）。
     */
    CsTicketVO update(String ticketNo, TicketUpdateReq req);

    /**
     * 出口①：转工单（{@code POST /api/ops/cs/tickets/{ticketNo}/work-order}）。
     *
     * <p><b>幂等</b>：{@code wo_no} 已非空则直接返回既有单，不建第二张工单。
     */
    CsTicketVO toWorkOrder(String ticketNo);

    /**
     * 出口②：转退款申请（{@code POST /api/ops/cs/tickets/{ticketNo}/refund}）。
     *
     * <p><b>幂等</b>：{@code refund_no} 已非空则直接返回既有单，不建第二张退款申请
     * —— 重复退款是资金事故，幂等在这里不是优化而是硬要求（[db-design §1.6]）。
     */
    CsTicketVO toRefund(String ticketNo);

    /**
     * C 端自助报障（{@code POST /mp/user/report}）—— 建单 + 按字典自动分流。
     *
     * @param cUserNo 报障人，**只能来自 ConsumerContext**，不接受前端传参
     */
    ReportResultVO report(String cUserNo, ReportReq req);

    /** C 端「我的报障」列表（{@code GET /mp/user/reports}），强制按属主过滤。 */
    PageResult<CsTicketVO> pageByUser(Integer page, Integer size, String cUserNo, String status);

    /**
     * C 端报障详情（{@code GET /mp/user/reports/{reportNo}}）。
     * 非本人的单一律当作不存在（防 IDOR 探测：返回 403 会泄露单号是否存在）。
     */
    CsTicketVO getForUser(String cUserNo, String reportNo);
}
