package ai.neargo.sharehub.cs.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.ServerException;
import ai.neargo.common.core.ErrorCode;
import ai.neargo.sharehub.cs.CsTicketStatus;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.cs.ProblemActionResolver;
import ai.neargo.sharehub.cs.dto.CsDtos.CsTicketVO;
import ai.neargo.sharehub.cs.dto.CsDtos.ReportReq;
import ai.neargo.sharehub.cs.dto.CsDtos.ReportResultVO;
import ai.neargo.sharehub.cs.dto.CsDtos.TicketCreateReq;
import ai.neargo.sharehub.cs.dto.CsDtos.TicketUpdateReq;
import ai.neargo.sharehub.cs.entity.CsTicket;
import ai.neargo.sharehub.cs.mapper.CsTicketMapper;
import ai.neargo.sharehub.cs.port.TicketRefundPort;
import ai.neargo.sharehub.cs.port.TicketWorkOrderPort;
import ai.neargo.sharehub.cs.service.CsSessionService;
import ai.neargo.sharehub.cs.service.CsTicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ai.neargo.sharehub.api.platform.dto.FileRef;
import ai.neargo.sharehub.api.platform.port.FileBindingPort;
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

    private static final Logger log = LoggerFactory.getLogger(CsTicketServiceImpl.class);

    private static final String TENANT_MAIN = "MAIN";
    /** 与 {@code FileCategory.CS_EVIDENCE} 的 bizTypes 对应 —— 绑定时会校验用途与它相符。 */
    private static final String BIZ_CS_TICKET = "CS_TICKET";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 状态机：当前态 → 允许迁往的态。CLOSED 是终态。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            CsTicketStatus.OPEN.name(), Set.of(CsTicketStatus.PROCESSING.name(), CsTicketStatus.CLOSED.name()),
            CsTicketStatus.PROCESSING.name(), Set.of(CsTicketStatus.CLOSED.name()),
            CsTicketStatus.CLOSED.name(), Set.of());

    private final CsTicketMapper mapper;
    private final ProblemActionResolver actionResolver;
    private final TicketWorkOrderPort workOrderPort;
    private final TicketRefundPort refundPort;
    private final CsSessionService sessionService;
    private final FileBindingPort fileBinding;

    public CsTicketServiceImpl(CsTicketMapper mapper,
                               ProblemActionResolver actionResolver,
                               TicketWorkOrderPort workOrderPort,
                               TicketRefundPort refundPort,
                               CsSessionService sessionService,
                               FileBindingPort fileBinding) {
        this.mapper = mapper;
        this.actionResolver = actionResolver;
        this.workOrderPort = workOrderPort;
        this.refundPort = refundPort;
        this.sessionService = sessionService;
        this.fileBinding = fileBinding;
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
        return e == null ? null : withPhotos(e);
    }

    /**
     * 运营端手工建单 —— **刻意不走分流**。
     *
     * <p>{@link #report} 会按 {@code problemNo} 查字典决定自助/转工单/转退款，
     * 其中「自助解决」直接把单关掉。客服接到来电时人已经在处理了，
     * 再被判成自助解决关单，等于把刚登记的诉求当场丢掉。
     *
     * <p>状态默认 {@link CsTicketStatus#OPEN}；前端表单允许直接选 PROCESSING
     * （客服边接电话边处理），所以入参给了就用入参 —— 但**必须是合法取值**，
     * 走 {@code CsTicketStatus.of} 而不是原样落库。
     *
     * <p>{@code ticketNo} 留空自动生成（表单占位符就是这么写的）；给了则沿用，
     * 便于把线下已有的单号登记进来。
     */
    @Override
    @Transactional
    public CsTicketVO create(TicketCreateReq req) {
        if (req == null || req.issue() == null || req.issue().isBlank()) {
            // 没有问题描述的工单是一行空记录 —— 它会进列表、占 SLA，却谁也不知道要做什么
            throw BizException.badRequest("error.report.issue_required");
        }
        CsTicket e = new CsTicket();
        e.setTicketNo(req.ticketNo() == null || req.ticketNo().isBlank() ? nextTicketNo() : req.ticketNo().trim());
        e.setTenantId(TENANT_MAIN);
        e.setCUserNo(blank(req.userNo()));
        e.setOrderNo(blank(req.orderNo()));
        e.setCabinetNo(blank(req.cabinetNo()));
        e.setProblemNo(blank(req.problemNo()));
        e.setIssue(req.issue().trim());
        // 原先是 req.channel().trim() 原样落库 —— 用例因此写进过一行 channel='电话'，
        // 而那一个异形值把整列从 StoredValueInVocabularyTest 的视野里抹掉了（见 CsTicketChannel 类注释）
        e.setChannel(req.channel() == null || req.channel().isBlank()
                ? ai.neargo.sharehub.cs.CsTicketChannel.MANUAL.name()
                : ai.neargo.sharehub.cs.CsTicketChannel.of(req.channel()).name());
        e.setStatus(req.status() == null || req.status().isBlank()
                ? CsTicketStatus.OPEN.name()
                : CsTicketStatus.of(req.status()).name());
        mapper.insert(e);
        return toVO(e);
    }

    /** 空串归一成 null：落库存空串会让「有没有填」这个问题在查询里分成两种写法。 */
    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
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
        e.setChannel(req == null || req.channel() == null || req.channel().isBlank()
                ? ai.neargo.sharehub.cs.CsTicketChannel.APP.name()
                : ai.neargo.sharehub.cs.CsTicketChannel.of(req.channel()).name());
        e.setStatus(CsTicketStatus.OPEN.name());
        mapper.insert(e); // 受理单必建 —— 分流失败也不能丢用户诉求

        // 截图归到这张单上。绑定失败（别人的文件 / 已绑过 / 用途不符）整单失败：
        // 与其把诉求收下、把证据丢掉，不如让用户当场重来 —— 丢了的证据事后补不回来。
        List<String> fileNos = req == null ? null : req.fileNos();
        if (fileNos != null && !fileNos.isEmpty()) {
            fileBinding.bind(fileNos, BIZ_CS_TICKET, e.getTicketNo(), null);
        }

        // 分流依据来自字典，不是 if-else 硬编码（[api/README §6A.2]）
        String action = actionResolver.resolve(e.getProblemNo());
        action = action == null ? ProblemActionResolver.TO_CS : action;
        String sessionNo = null;
        switch (action) {
            case ProblemActionResolver.SELF_SERVICE -> e.setStatus(CsTicketStatus.CLOSED.name()); // 自助解决，直接关单
            case ProblemActionResolver.TO_WORKORDER -> {
                e.setWoNo(workOrderPort.createWorkOrder(e));
                e.setStatus(CsTicketStatus.PROCESSING.name());
            }
            case ProblemActionResolver.TO_REFUND -> {
                /*
                 * 退款端口走不通（没关联订单 / 订单查不到 / 没有可退金额）时返回 null ——
                 * **改走人工**，不要留一个空的 refundNo。
                 * 空号的后果与旧的桩一样：单子看起来「已转退款」，而财务侧什么都没有。
                 */
                String refundNo = refundPort.createRefund(e);
                if (refundNo == null || refundNo.isBlank()) {
                    action = ProblemActionResolver.TO_CS;
                    sessionNo = sessionService.openFor(cUserNo, e.getIssue());
                    log.warn("报障转退款走不通，已改走人工 ticketNo={} orderNo={} —— "
                            + "多半是这条问题配了 TO_REFUND 但用户的诉求没关联订单，请核对问题字典",
                            e.getTicketNo(), e.getOrderNo());
                } else {
                    e.setRefundNo(refundNo);
                }
                e.setStatus(CsTicketStatus.PROCESSING.name());
            }
            default -> { // TO_CS 及一切未知值：转人工，最安全的兜底方向
                sessionNo = sessionService.openFor(cUserNo, e.getIssue());
                e.setStatus(CsTicketStatus.PROCESSING.name());
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
        return e == null ? null : withPhotos(e);
    }

    // ——————————————————————— 内部 ———————————————————————

    /** 详情才查附件：列表逐行查是 N+1，而列表上也放不下图。 */
    private CsTicketVO withPhotos(CsTicket e) {
        CsTicketVO vo = toVO(e);
        List<FileRef> photos = fileBinding.listBound(BIZ_CS_TICKET, e.getTicketNo());
        return new CsTicketVO(vo.ticketNo(), vo.userNo(), vo.orderNo(), vo.cabinetNo(), vo.problemNo(),
                vo.issue(), vo.channel(), vo.status(), vo.handlerNo(), vo.woNo(), vo.refundNo(),
                vo.createdAt(), photos, photos.size());
    }

    private PageResult<CsTicketVO> doPage(Integer page, Integer size, LambdaQueryWrapper<CsTicket> w) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);
        w.orderByDesc(CsTicket::getId);
        Page<CsTicket> r = mapper.selectPage(new Page<>(p, s), w);
        java.util.Map<String, Integer> counts = fileBinding.countBound(BIZ_CS_TICKET,
                r.getRecords().stream().map(CsTicket::getTicketNo).toList());
        List<CsTicketVO> rows = r.getRecords().stream()
                .map(CsTicketServiceImpl::toVO)
                .map(vo -> vo.withPhotoCount(counts.getOrDefault(vo.ticketNo(), 0)))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    private CsTicket find(String ticketNo) {
        if (ticketNo == null || ticketNo.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<CsTicket>()
                .eq(CsTicket::getTicketNo, ticketNo).last("limit 1"));
    }

    private CsTicket require(String ticketNo) {
        CsTicket e = find(ticketNo);
        if (e == null) throw BizException.notFound(ticketNo);
        return e;
    }

    /** 转出后推进到 PROCESSING；已 CLOSED 的单不回退（转出是对既有诉求的补处置，不复活工单）。 */
    private void advanceToProcessing(CsTicket e) {
        if (CsTicketStatus.OPEN.name().equals(e.getStatus())) e.setStatus(CsTicketStatus.PROCESSING.name());
    }

    private void assertTransition(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from == null ? CsTicketStatus.OPEN.name() : from);
        if (allowed == null) throw new IllegalStateException("未知报障状态: " + from);
        if (!allowed.contains(to)) {
            throw ServerException.of(ErrorCode.CONFLICT, "报障状态非法迁移: " + from + " → " + to);
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
