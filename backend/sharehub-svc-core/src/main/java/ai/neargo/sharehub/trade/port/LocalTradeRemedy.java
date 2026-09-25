package ai.neargo.sharehub.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.core.port.TradeRemedyPort;
import ai.neargo.sharehub.trade.OrderStatus;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.trade.service.RentOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** {@link TradeRemedyPort} 的本地实现：委托订单服务，按状态条件执行（幂等）。 */
@Service
public class LocalTradeRemedy implements TradeRemedyPort {

    private static final Logger log = LoggerFactory.getLogger(LocalTradeRemedy.class);

    private final RentOrderService orders;
    private final OrdMapper mapper;

    public LocalTradeRemedy(RentOrderService orders, OrdMapper mapper) {
        this.orders = orders;
        this.mapper = mapper;
    }

    @Override
    public boolean cancelUndelivered(String orderNo, String reason) {
        boolean done = orders.cancelUndelivered(orderNo, reason);
        if (done) log.info("自愈撤销未出宝订单 orderNo={}", orderNo);
        return done || !OrderStatus.DISPENSING.name().equals(statusOf(orderNo));
    }

    @Override
    public boolean finishAt(String orderNo, String cabinetNo, Integer slotIndex, LocalDateTime returnedAt) {
        if (!OrderStatus.IN_USE.name().equals(statusOf(orderNo))) return true;   // 已被正常归还或人工处理
        // 订单时间列按 UTC 存（RentOrderServiceImpl.nowUtc）；识别时刻是本地时间，换算后作为计费截止
        LocalDateTime endUtc = returnedAt == null ? null
                : returnedAt.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        DataScopeContext.executeWithoutScope(() -> orders.returnOrderAt(orderNo, cabinetNo, endUtc));
        log.info("自愈按识别时刻结单 orderNo={} cabinetNo={} slot={} endUtc={}", orderNo, cabinetNo, slotIndex, endUtc);
        return true;
    }

    private String statusOf(String orderNo) {
        OrdOrder o = DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                new LambdaQueryWrapper<OrdOrder>().eq(OrdOrder::getOrderNo, orderNo).last("limit 1")));
        return o == null ? null : o.getStatus();
    }
}
