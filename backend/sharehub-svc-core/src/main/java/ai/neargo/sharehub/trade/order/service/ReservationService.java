package ai.neargo.sharehub.trade.order.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.Reservation;

/**
 * 预约订单业务（{@code ord_reservation}）。
 *
 * <p>{@link #cancel} **只允许从 {@code PENDING} 出发**，且服务端必须复校 ——
 * 前端置灰按钮只是乐观提示，直接打接口能绕过；已履约/已过期的预约再取消会让占位费口径错乱。
 */
public interface ReservationService {

    PageResult<Reservation> page(Integer page, Integer size, String keyword, String status, String type);

    /** 取消预约。非 PENDING 抛异常（服务端复校，不信前端）。 */
    OkResult cancel(String reservationNo);
}
