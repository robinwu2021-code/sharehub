package ai.neargo.sharehub.trade.order.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.ExceptionHandleReq;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.OrderException;

/**
 * 异常订单业务（{@code ord_exception}）。
 *
 * <p>状态只有 {@code OPEN → HANDLED}，重复处置直接拒（幂等靠显式校验而非静默吞掉，
 * 否则第二个处置人会以为自己的处置生效了）。
 */
public interface OrderExceptionService {

    PageResult<OrderException> page(Integer page, Integer size, String keyword, String status, String type);

    /** 处置。处置人/处置时间由服务端按当前登录人回填；非 OPEN 状态抛异常。 */
    OkResult handle(String exceptionNo, ExceptionHandleReq req);
}
