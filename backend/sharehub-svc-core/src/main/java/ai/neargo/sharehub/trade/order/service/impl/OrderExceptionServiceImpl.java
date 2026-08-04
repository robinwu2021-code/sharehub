package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.ExceptionHandleReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderException;
import ai.neargo.sharehub.trade.order.entity.OrdException;
import ai.neargo.sharehub.trade.order.mapper.OrdExceptionMapper;
import ai.neargo.sharehub.trade.order.service.OrderEventLogService;
import ai.neargo.sharehub.trade.order.service.OrderExceptionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 异常订单实现。状态机只有一步 {@code OPEN → HANDLED}，用显式校验即可，不必上状态机类。
 *
 * <p>处置会同时往 {@code ord_event_log} 补一条时间线 —— 异常处置改变了订单的实际结局，
 * 订单详情页看不到就成了黑箱。
 */
@Service
public class OrderExceptionServiceImpl implements OrderExceptionService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_HANDLED = "HANDLED";

    /**
     * 业务键前缀。**{@code BizKey} 尚未登记异常订单前缀**（[db-design §1.4.1] 注册表也缺），
     * 暂用 {@code OEX}；登记后应改为引用 {@code BizKey} 常量。见交付报告。
     */
    private static final String KEY_PREFIX = "OEX";
    private static final int KEY_WIDTH = 6;

    private final OrdExceptionMapper mapper;
    private final OrderEventLogService eventLog;

    public OrderExceptionServiceImpl(OrdExceptionMapper mapper, OrderEventLogService eventLog) {
        this.mapper = mapper;
        this.eventLog = eventLog;
    }

    @Override
    public PageResult<OrderException> page(Integer page, Integer size, String keyword, String status, String type) {
        LambdaQueryWrapper<OrdException> w = new LambdaQueryWrapper<>();
        if (OrderSupport.has(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(OrdException::getExceptionNo, kw)
                    .or().like(OrdException::getOrderNo, kw)
                    .or().like(OrdException::getCabinetNo, kw)
                    .or().like(OrdException::getCUserNo, kw));
        }
        if (OrderSupport.has(status)) w.eq(OrdException::getStatus, status);
        if (OrderSupport.has(type)) w.eq(OrdException::getType, type);
        w.orderByDesc(OrdException::getId);

        Page<OrdException> r = mapper.selectPage(new Page<>(OrderSupport.page(page), OrderSupport.size(size)), w);
        List<OrderException> rows = r.getRecords().stream().map(OrderExceptionServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public OkResult handle(String exceptionNo, ExceptionHandleReq req) {
        OrdException e = selectByNo(exceptionNo);
        if (e == null) throw new IllegalArgumentException("异常订单不存在: " + exceptionNo);
        if (!STATUS_OPEN.equals(e.getStatus())) {
            // 不静默返回成功：第二个处置人必须知道自己扑了个空，否则会以为处置生效了
            throw new IllegalStateException("异常订单非 OPEN 状态，不可处置: " + exceptionNo + " → " + e.getStatus());
        }

        e.setStatus(STATUS_HANDLED);
        e.setHandledBy(OrderSupport.currentNo());   // 服务端回填，不信前端传的处置人
        e.setHandledAt(OrderSupport.now());
        // 处置动作/结果与下游单据回填（V31）：action 归一为存储值域；下游单号只记引用不在此产生
        if (req != null) {
            String action = req.action();
            if (action != null && !action.isBlank()) {
                e.setHandleAction(switch (action) {
                    case "work_order" -> "WORK_ORDER";
                    case "refund" -> "REFUND";
                    case "close" -> "IGNORE";
                    default -> throw new IllegalArgumentException("处置动作非法: " + action);
                });
            }
            if (req.handleResult() != null && !req.handleResult().isBlank()) e.setHandleResult(req.handleResult());
            if (req.refundNo() != null && !req.refundNo().isBlank()) e.setRefundNo(req.refundNo());
            if (req.workOrderNo() != null && !req.workOrderNo().isBlank()) e.setWorkOrderNo(req.workOrderNo());
        }
        mapper.updateById(e);

        String note = req == null ? null : req.note();
        eventLog.append(e.getOrderNo(), STATUS_OPEN, STATUS_HANDLED,
                note == null || note.isBlank() ? "EXCEPTION_HANDLE" : "EXCEPTION_HANDLE:" + note,
                OrderSupport.currentName());
        return new OkResult(true);
    }

    /** 新建异常单（供本域/网关侧内部调用；无对外端点——异常由系统探测产生，不由人工登记）。 */
    String create(OrdException e) {
        e.setExceptionNo(OrderSupport.nextNo(mapper, "exception_no", KEY_PREFIX, KEY_WIDTH,
                OrdException::getExceptionNo));
        if (e.getTenantId() == null) e.setTenantId(OrderSupport.TENANT_MAIN);
        if (e.getStatus() == null) e.setStatus(STATUS_OPEN);
        mapper.insert(e);
        return e.getExceptionNo();
    }

    private OrdException selectByNo(String no) {
        if (!OrderSupport.has(no)) return null;
        return mapper.selectOne(new LambdaQueryWrapper<OrdException>()
                .eq(OrdException::getExceptionNo, no).last("limit 1"));
    }

    private static OrderException toVO(OrdException e) {
        return new OrderException(e.getExceptionNo(), e.getOrderNo(), e.getType(), e.getCabinetNo(),
                e.getCUserNo(), e.getAmount(), e.getCurrency(), e.getStatus(),
                e.getHandledBy(), e.getHandledAt(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
                e.getHandleAction(), e.getHandleResult(), e.getRefundNo(), e.getWorkOrderNo());
    }
}
