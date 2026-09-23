package ai.neargo.sharehub.portal.ops;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.StaffContext;
import ai.neargo.sharehub.cs.dto.CsDtos.CsMessageVO;
import ai.neargo.sharehub.cs.dto.CsDtos.CsSessionVO;
import ai.neargo.sharehub.cs.dto.CsDtos.CsTicketVO;
import ai.neargo.sharehub.cs.dto.CsDtos.ReplyReq;
import ai.neargo.sharehub.cs.dto.CsDtos.TicketCreateReq;
import ai.neargo.sharehub.cs.dto.CsDtos.TicketUpdateReq;
import ai.neargo.sharehub.cs.service.CsSessionService;
import ai.neargo.sharehub.cs.service.CsTicketService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客服管理运营端端点（[api/README §3.6]）—— 报障受理 + 客服会话两个菜单叶。
 *
 * <p>两个转出端点的权限码**故意借用目标域的码**而不是 cs 自己的：
 * {@code /work-order} 用 {@code workorder:wo:create}、{@code /refund} 用 {@code order:refund:apply}。
 * 理由是这两个动作的实质后果发生在工单域和资金域 —— 谁能建工单/发起退款，应该由那两个域的授权说了算，
 * 而不是「有客服权限就能凭空造退款单」。
 */
@RestController
@RequestMapping("/api/ops/cs")
public class CsController {

    private final CsTicketService ticketService;
    private final CsSessionService sessionService;

    public CsController(CsTicketService ticketService, CsSessionService sessionService) {
        this.ticketService = ticketService;
        this.sessionService = sessionService;
    }

    // ——————————————— 报障受理 ———————————————

    @GetMapping("/tickets")
    @PreAuthorize("@perm.can('cs:ticket:read')")
    public PageResult<CsTicketVO> tickets(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String channel) {
        return ticketService.page(page, size, keyword, status, channel);
    }

    /** 受理 / 更新（状态、处理人、补充描述）。非法状态迁移由 service 抛异常。 */
    /**
     * 运营端手工建单（客服接到来电后登记）。
     *
     * <p>2026-09-23 补：此前只有 {@code POST /tickets/{ticketNo}}（受理/更新），
     * 而页面右上就有「新增工单」按钮、空态文案也写着「也可点右上『新增工单』手工建单」——
     * **按钮在、接口不在**，点了 404。本地 ops-web 跑 mock 所以一直没暴露。
     *
     * <p>与 C 端 {@code POST /mp/user/report} 的区别在 service：那边走分流
     * （可能判为自助解决直接关单），这边不走。
     */
    @PostMapping("/tickets")
    @PreAuthorize("@perm.can('cs:ticket:handle')")
    public CsTicketVO createTicket(@RequestBody TicketCreateReq body) {
        return ticketService.create(body);
    }

    @PostMapping("/tickets/{ticketNo}")
    @PreAuthorize("@perm.can('cs:ticket:update')")
    public CsTicketVO updateTicket(@PathVariable String ticketNo, @RequestBody TicketUpdateReq body) {
        return ticketService.update(ticketNo, body);
    }

    /** 出口①：报障 → 转工单。**幂等** —— 已转过则原样返回既有 {@code woNo}。 */
    @PostMapping("/tickets/{ticketNo}/work-order")
    @PreAuthorize("@perm.can('workorder:wo:create')")
    public CsTicketVO toWorkOrder(@PathVariable String ticketNo) {
        return ticketService.toWorkOrder(ticketNo);
    }

    /** 出口②：报障 → 转退款申请。**幂等** —— 已转过则原样返回既有 {@code refundNo}。 */
    @PostMapping("/tickets/{ticketNo}/refund")
    @PreAuthorize("@perm.can('order:refund:apply')")
    public CsTicketVO toRefund(@PathVariable String ticketNo) {
        return ticketService.toRefund(ticketNo);
    }

    // ——————————————— 客服会话 ———————————————

    @GetMapping("/sessions")
    @PreAuthorize("@perm.can('cs:session:read')")
    public PageResult<CsSessionVO> sessions(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String status) {
        return sessionService.page(page, size, keyword, status);
    }

    @GetMapping("/sessions/{sessionNo}/messages")
    @PreAuthorize("@perm.can('cs:session:read')")
    public List<CsMessageVO> messages(@PathVariable String sessionNo,
                                      @RequestParam(required = false) Integer limit) {
        return sessionService.messages(sessionNo, limit);
    }

    /** 客服回复。发送人取自登录态而非请求体 —— 让员工能冒名他人回复是审计上的洞。 */
    @PostMapping("/sessions/{sessionNo}/messages")
    @PreAuthorize("@perm.can('cs:session:reply')")
    public CsMessageVO reply(@PathVariable String sessionNo, @RequestBody ReplyReq body) {
        return sessionService.reply(sessionNo, StaffContext.require().userNo(), body.content(), body.attach());
    }
}
