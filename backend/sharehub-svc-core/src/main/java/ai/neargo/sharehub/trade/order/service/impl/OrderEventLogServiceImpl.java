package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderEvent;
import ai.neargo.sharehub.trade.order.entity.OrdEventLog;
import ai.neargo.sharehub.trade.order.mapper.OrdEventLogMapper;
import ai.neargo.sharehub.trade.order.service.OrderEventLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 订单状态时间线实现。append-only：只有 insert 与按单号正序查，没有 update/delete。 */
@Service
public class OrderEventLogServiceImpl implements OrderEventLogService {

    private final OrdEventLogMapper mapper;

    public OrderEventLogServiceImpl(OrdEventLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(String orderNo, String fromStatus, String toStatus, String event, String operator) {
        if (!OrderSupport.has(orderNo)) return; // 无单号的事件挂不上时间线，直接丢弃好过写脏数据
        OrdEventLog e = new OrdEventLog();
        e.setOrderNo(orderNo);
        e.setFromStatus(fromStatus);
        e.setToStatus(toStatus);
        e.setEvent(event);
        e.setOperator(operator);
        e.setCreatedAt(OrderSupport.now());
        mapper.insert(e);
    }

    @Override
    public List<OrderEvent> timeline(String orderNo) {
        List<OrdEventLog> rows = mapper.selectList(new LambdaQueryWrapper<OrdEventLog>()
                .eq(OrdEventLog::getOrderNo, orderNo)
                .orderByAsc(OrdEventLog::getId)); // 时间正序；同毫秒时按自增 id 定序，比 created_at 稳
        return rows.stream().map(OrderEventLogServiceImpl::toVO).toList();
    }

    private static OrderEvent toVO(OrdEventLog e) {
        return new OrderEvent(e.getOrderNo(), e.getFromStatus(), e.getToStatus(),
                e.getEvent(), e.getOperator(), e.getCreatedAt());
    }
}
