package ai.neargo.sharehub.cs.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.cs.ProblemActionResolver;
import ai.neargo.sharehub.cs.dto.CsDtos.CsTicketVO;
import ai.neargo.sharehub.cs.dto.CsDtos.ReportReq;
import ai.neargo.sharehub.cs.dto.CsDtos.ReportResultVO;
import ai.neargo.sharehub.cs.dto.CsDtos.TicketUpdateReq;
import ai.neargo.sharehub.cs.entity.CsTicket;
import ai.neargo.sharehub.cs.mapper.CsTicketMapper;
import ai.neargo.sharehub.cs.port.TicketRefundPort;
import ai.neargo.sharehub.cs.port.TicketWorkOrderPort;
import ai.neargo.sharehub.cs.service.CsSessionService;
import ai.neargo.sharehub.cs.service.CsTicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报障受理实现 —— 聚合根手写风格（{@code LambdaQueryWrapper} + 显式状态校验）。
 *
 * <p>三条不变式，读代码时请守住：
 * <ol>
 *   <li><b>分流不硬编码</b>：动作一律问 {@link ProblemActionResolver}（背后是 md_problem 字典）；</li>
 *   <li><b>出口幂等</b>：{@code woNo}/{@code refundNo} 非空即返回既有值，绝不建第二张单；</li>
 *   <li><b>状态单向</b>：OPEN → PROCESSING → CLOSED，回退与跳级都抛异常。</li>
 * </ol>
 */
@Service
public class CsTicketServiceImpl implements CsTicketService {

    private static final String TENANT_MAIN = "MAIN";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 状态机：当前态 → 允许迁往的态。CLOSED 是终态。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of("PROCESSING", "CLOSED"),
            "PROCESSING", Set.of("CLOSED"),
            "CLOSED", Set.of());

    private final CsTicketMapper mapper;
    private final ProblemActionResolver actionResolver;
    private final TicketWorkOrderPort workOrderPort;
    private final TicketRefundPort refundPort;
    private final CsSessionService sessionService;

    public CsTicketServiceImpl(CsTicketMapper mapper,
                               ProblemActionResolver actionResolver,
                               TicketWorkOrderPort workOrderPort,
                               TicketRefundPort refundPort,
                               CsSessionService sessionService) {
        this.mapper = mapper;
        this.actionResolver = actionResolver;
        this.workOrderPort = workOrderPort;
        this.refundPort = refundPort;
        this.sessionService = sessionService;
    }

    // ——————————————————————— 运营端 ———————————————————————

    @Override
    public PageResult<CsTicketVO> page(Integer page, Integer size, String keyword, String status, String channel) {
        LambdaQueryWrapper<CsTicket> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(CsTicket::getTicketNo, keyword)
                    .or().like(CsTicket::getCUserNo, keyword)
                    .or().like(CsTicket::getOrderNo, keyword)
                    .or().like(CsTicket::getCabinetNo, keyword));
        }
        w.eq(status != null && !status.isBlank(), CsTicket::getStatus, status);
        w.eq(channel != null && !channel.isBlank(), CsTicket::getChannel, channel);
        return doPage(page, size, w);
    }

    @Override
    public CsTicketVO get(String ticketNo) {
        CsTicket e = find(ticketNo);
        return e == null ? null : toVO(e);
    }

    @Override
    @Transactional
    public CsTicketVO update(String ticketNo, TicketUpdateReq req) {
        CsTicket e = require(ticketNo);
        if (req == null) return toVO(e);

        if (req.status() != null && !req.status().isBlank() && !req.status().equals(e.getStatus())) {
            assertTransition(e.getStatus(), req.status());
            e.setStatus(req.status());
        }
        if (req.handlerNo() != null && !req.handlerNo().isBlank()) e.setHandlerNo(req.handlerNo());
        if (req.issue() != null && !req.issue().isBlank()) e.setIssue(req.issue());

        mapper.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public CsTicketVO toWorkOrder(String ticketNo) {
        CsTicket e = require(ticketNo);

        // 幂等闸门：已转过就原样返回，不再建第二张工单
        if (notBlank(e.getWoNo())) return toVO(e);

        e.setWoNo(workOrderPort.createWorkOrder(e));
        advanceToProcessing(e);
        mapper.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public CsTicketVO toRefund(String ticketNo) {
        CsTicket e = require(ticketNo);

        // 幂等闸门：重复退款是资金事故，这道判断不可省
        if (notBlank(e.getRefundNo())) return toVO(e);

        e.setRefundNo(refundPort.createRefund(e));
        advanceToProcessing(e);
        mapper.updateById(e);
        return toVO(e);
    }

    // ——————————————————————— C 端 ———————————————————————

    @Override
    @Transactional
    public ReportResultVO report(String cUserNo, ReportReq req) {
        CsTicket e = new CsTicket();
        e.setTicketNo(nextTicketNo());
        e.setTenantId(TENANT_MAIN);
        e.setCUserNo(cUserNo);
        e.setOrderNo(req == null ? null : req.orderNo());
        e.setCabinetNo(req == null ? null : req.cabinetNo());
        e.setProblemNo(req == null ? null : req.problemNo());
        e.setIssue(req == null ? null : req.issue());
        e.setChannel(req == null || req.channel() == null || req.channel().isBlank() ? "APP" : req.channel());
        e.setStatus("OPEN");
        mapper.insert(e); // 受理单必建 —— 分流失败也不能丢用户诉求

        // 分流依据来自字典，不是 if-else 硬编码（[api/README §6A.2]）
        String action = actionResolver.resolve(e.getProblemNo());
        String sessionNo = null;
        switch (action == null ? ProblemActionResolver.TO_CS : action) {
            case ProblemActionResolver.SELF_SERVICE -> e.setStatus("CLOSED"); // 自助解决，直接关单
            case ProblemActionResolver.TO_WORKORDER -> {
                e.setWoNo(workOrderPort.createWorkOrder(e));
                e.setStatus("PROCESSING");
            }
            case ProblemActionResolver.TO_REFUND -> {
                e.setRefundNo(refundPort.createRefund(e));
                e.setStatus("PROCESSING");
            }
            default -> { // TO_CS 及一切未知值：转人工，最安全的兜底方向
                sessionNo = sessionService.openFor(cUserNo, e.getIssue());
                e.setStatus("PROCESSING");
            }
        }
        mapper.updateById(e);

        return new ReportResultVO(e.getTicketNo(), e.getStatus(), action,
                e.getWoNo(), e.getRefundNo(), sessionNo,
                e.getCreatedAt() == null ? null : e.getCreatedAt().format(TS));
    }

    @Override
    public PageResult<CsTicketVO> pageByUser(Integer page, Integer size, String cUserNo, String status) {
        LambdaQueryWrapper<CsTicket> w = new LambdaQueryWrapper<CsTicket>()
                .eq(CsTicket::getCUserNo, cUserNo) // 属主过滤不可选，必须恒生效
                .eq(status != null && !status.isBlank(), CsTicket::getStatus, status);
        return doPage(page, size, w);
    }

    @Override
    public CsTicketVO getForUser(String cUserNo, String reportNo) {
        CsTicket e = mapper.selectOne(new LambdaQueryWrapper<CsTicket>()
                .eq(CsTicket::getTicketNo, reportNo)
                .eq(CsTicket::getCUserNo, cUserNo) // 他人单号查不到 = 不存在，不泄露存在性
                .last("limit 1"));
        return e == null ? null : toVO(e);
    }

    // ——————————————————————— 内部 ———————————————————————

    private PageResult<CsTicketVO> doPage(Integer page, Integer size, LambdaQueryWrapper<CsTicket> w) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);
        w.orderByDesc(CsTicket::getId);
        Page<CsTicket> r = mapper.selectPage(new Page<>(p, s), w);
        List<CsTicketVO> rows = r.getRecords().stream().map(CsTicketServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    private CsTicket find(String ticketNo) {
        if (ticketNo == null || ticketNo.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<CsTicket>()
                .eq(CsTicket::getTicketNo, ticketNo).last("limit 1"));
    }

    private CsTicket require(String ticketNo) {
        CsTicket e = find(ticketNo);
        if (e == null) throw new IllegalArgumentException("报障单不存在: " + ticketNo);
        return e;
    }

    /** 转出后推进到 PROCESSING；已 CLOSED 的单不回退（转出是对既有诉求的补处置，不复活工单）。 */
    private void advanceToProcessing(CsTicket e) {
        if ("OPEN".equals(e.getStatus())) e.setStatus("PROCESSING");
    }

    private void assertTransition(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from == null ? "OPEN" : from);
        if (allowed == null) throw new IllegalStateException("未知报障状态: " + from);
        if (!allowed.contains(to)) {
            throw new IllegalStateException("报障状态非法迁移: " + from + " → " + to);
        }
    }

    /** 扫描同前缀最大号 +1（[db-design §1.4.1]）；并发撞号由 uk_ticket_no 兜底。 */
    private String nextTicketNo() {
        CsTicket top = mapper.selectOne(new LambdaQueryWrapper<CsTicket>()
                .likeRight(CsTicket::getTicketNo, BizKey.CS_TICKET)
                .orderByDesc(CsTicket::getTicketNo)
                .last("limit 1"));
        long n = 0L;
        if (top != null && top.getTicketNo() != null && top.getTicketNo().length() > BizKey.CS_TICKET.length()) {
            String digits = top.getTicketNo().substring(BizKey.CS_TICKET.length()).replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                try {
                    n = Long.parseLong(digits);
                } catch (NumberFormatException ignore) {
                    // 历史脏号不参与取号
                }
            }
        }
        return BizKey.CS_TICKET + String.format("%06d", n + 1);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static CsTicketVO toVO(CsTicket e) {
        return new CsTicketVO(e.getTicketNo(), e.getCUserNo(), e.getOrderNo(), e.getCabinetNo(),
                e.getProblemNo(), e.getIssue(), e.getChannel(), e.getStatus(),
                e.getHandlerNo(), e.getWoNo(), e.getRefundNo(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().format(TS));
    }
}
