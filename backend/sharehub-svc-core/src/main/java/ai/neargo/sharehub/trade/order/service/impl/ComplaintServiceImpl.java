package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.common.core.ServerException;
import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.ComplaintCreateReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.ComplaintHandleReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderComplaint;
import ai.neargo.sharehub.trade.order.entity.OrdComplaint;
import ai.neargo.sharehub.trade.order.mapper.OrdComplaintMapper;
import ai.neargo.sharehub.trade.order.service.ComplaintService;
import ai.neargo.sharehub.trade.order.service.OrderEventLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 投诉订单实现。
 *
 * <p>三条业务规则落在这里：
 * <ol>
 *   <li>登记时状态恒为 {@code PENDING}，{@code submittedAt} 服务端打点；</li>
 *   <li>处理时 {@code handlerName}/{@code handledAt} 服务端回填，结果枚举白名单校验，
 *       {@code REJECT} → 状态 {@code REJECTED}，其余 → {@code RESOLVED}；</li>
 *   <li><b>转工单幂等</b>：{@code wo_no} 非空直接返已有值，绝不重复开单。</li>
 * </ol>
 */
@Service
public class ComplaintServiceImpl implements ComplaintService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String STATUS_REJECTED = "REJECTED";

    private static final Set<String> ISSUE_TYPES =
            Set.of("BILLING_DISPUTE", "NOT_EJECTED", "NOT_RETURNED", "DEVICE_FAULT", "OTHER");
    private static final Set<String> RESOLUTIONS =
            Set.of("REFUND", "COMPENSATE", "REJECT", "EXPLAINED");

    private static final int KEY_WIDTH = 6;

    private final OrdComplaintMapper mapper;
    private final OrderEventLogService eventLog;

    public ComplaintServiceImpl(OrdComplaintMapper mapper, OrderEventLogService eventLog) {
        this.mapper = mapper;
        this.eventLog = eventLog;
    }

    @Override
    public PageResult<OrderComplaint> page(Integer page, Integer size, String keyword,
                                           String status, String issueType) {
        LambdaQueryWrapper<OrdComplaint> w = new LambdaQueryWrapper<>();
        if (OrderSupport.has(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(OrdComplaint::getComplaintNo, kw)
                    .or().like(OrdComplaint::getOrderNo, kw)
                    .or().like(OrdComplaint::getCUserNo, kw));
        }
        if (OrderSupport.has(status)) w.eq(OrdComplaint::getStatus, status);
        if (OrderSupport.has(issueType)) w.eq(OrdComplaint::getIssueType, issueType);
        w.orderByDesc(OrdComplaint::getId);

        Page<OrdComplaint> r = mapper.selectPage(new Page<>(OrderSupport.page(page), OrderSupport.size(size)), w);
        List<OrderComplaint> rows = r.getRecords().stream().map(ComplaintServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public OrderComplaint create(ComplaintCreateReq req) {
        if (req == null || !OrderSupport.has(req.orderNo())) {
            throw new IllegalArgumentException("orderNo 必填");
        }
        if (!OrderSupport.has(req.userNo())) {
            throw new IllegalArgumentException("userNo 必填");
        }
        String issueType = OrderSupport.has(req.issueType()) ? req.issueType() : "OTHER";
        if (!ISSUE_TYPES.contains(issueType)) {
            throw new IllegalArgumentException("非法 issueType: " + issueType);
        }

        OrdComplaint e = new OrdComplaint();
        e.setComplaintNo(OrderSupport.nextNo(mapper, "complaint_no", BizKey.COMPLAINT, KEY_WIDTH,
                OrdComplaint::getComplaintNo));
        e.setTenantId(OrderSupport.TENANT_MAIN);
        e.setOrderNo(req.orderNo());
        e.setCUserNo(req.userNo());
        e.setIssueType(issueType);
        e.setDescription(req.description());
        e.setScreenshotUrl(req.screenshotUrl());
        e.setSubmittedAt(OrderSupport.now());   // 服务端打点，不采信前端时间
        e.setStatus(STATUS_PENDING);
        mapper.insert(e);

        eventLog.append(e.getOrderNo(), null, STATUS_PENDING, "COMPLAINT_CREATE", OrderSupport.currentName());
        return toVO(selectByNo(e.getComplaintNo()));
    }

    @Override
    public OrderComplaint handle(String complaintNo, ComplaintHandleReq req) {
        OrdComplaint e = require(complaintNo);
        if (req == null || !OrderSupport.has(req.resolution())) {
            throw new IllegalArgumentException("resolution 必填");
        }
        if (!RESOLUTIONS.contains(req.resolution())) {
            throw new IllegalArgumentException("非法 resolution: " + req.resolution());
        }
        if (STATUS_RESOLVED.equals(e.getStatus()) || STATUS_REJECTED.equals(e.getStatus())) {
            throw ServerException.of(ErrorCode.CONFLICT, "投诉已结案，不可重复处理: " + complaintNo + " → " + e.getStatus());
        }

        String from = e.getStatus();
        e.setResolution(req.resolution());
        e.setResolutionNote(req.resolutionNote());
        e.setStatus("REJECT".equals(req.resolution()) ? STATUS_REJECTED : STATUS_RESOLVED);
        e.setHandlerName(OrderSupport.currentName()); // 服务端回填处理人快照名
        e.setHandledAt(OrderSupport.now());
        mapper.updateById(e);

        // TODO(退款联动)：resolution=REFUND 时按 [api/README §4.1] 应内联建 ord_refund（RefundService#apply）。
        //  骨架阶段不做跨聚合根写，避免与「客服在订单页发起退款」的幂等键来源打架 —— 待幂等键生成策略定稿后接。
        eventLog.append(e.getOrderNo(), from, e.getStatus(),
                "COMPLAINT_HANDLE:" + req.resolution(), OrderSupport.currentName());
        return toVO(selectByNo(complaintNo));
    }

    @Override
    public OrderComplaint toWorkOrder(String complaintNo) {
        OrdComplaint e = require(complaintNo);

        // —— 幂等第一道：已转过就原样返回，绝不重复开单 ——
        // 第二道是 wo_order.source_ref 的 UNIQUE（[db-design §1.6]），并发时兜底。
        if (OrderSupport.has(e.getWoNo())) {
            return toVO(e);
        }

        // TODO(跨域依赖)：开单要写 wo_order，属 wo 域。现有 WorkOrderService 只有 page/dispatch，
        //  需由 wo 域补 `String createFromComplaint(String complaintNo, String orderNo, String desc)`
        //  （内部以 source_ref=complaintNo 保证 UNIQUE 幂等），本处再回填 e.setWoNo(...) + updateById。
        //  在此之前显式失败，好过静默返回一个没有工单的「成功」。
        throw new UnsupportedOperationException(
                "投诉转工单待接 wo 域 createFromComplaint（source_ref=" + complaintNo + "）");
    }

    private OrdComplaint require(String complaintNo) {
        OrdComplaint e = selectByNo(complaintNo);
        if (e == null) throw new IllegalArgumentException("投诉不存在: " + complaintNo);
        return e;
    }

    private OrdComplaint selectByNo(String no) {
        if (!OrderSupport.has(no)) return null;
        return mapper.selectOne(new LambdaQueryWrapper<OrdComplaint>()
                .eq(OrdComplaint::getComplaintNo, no).last("limit 1"));
    }

    private static OrderComplaint toVO(OrdComplaint e) {
        return new OrderComplaint(e.getComplaintNo(), e.getOrderNo(), e.getCUserNo(), e.getIssueType(),
                e.getDescription(), e.getScreenshotUrl(), e.getSubmittedAt(), e.getStatus(),
                e.getHandlerName(), e.getHandledAt(), e.getResolution(), e.getResolutionNote(),
                e.getWoNo());
    }
}
