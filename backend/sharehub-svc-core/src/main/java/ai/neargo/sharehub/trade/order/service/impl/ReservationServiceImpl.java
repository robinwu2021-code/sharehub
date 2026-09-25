package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.common.core.ServerException;
import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.Reservation;
import ai.neargo.sharehub.trade.order.entity.OrdReservation;
import ai.neargo.sharehub.trade.order.mapper.OrdReservationMapper;
import ai.neargo.sharehub.trade.order.service.ReservationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 预约订单实现。
 *
 * <p>{@link #cancel} 的状态复校是这个类存在的理由：前端按钮置灰只挡住了误点，
 * 直接打接口能绕过；{@code FULFILLED} 已经变成租借单、{@code EXPIRED} 已经计过占位费，
 * 再走一次取消会让两边的口径都对不上。
 */
@Service
public class ReservationServiceImpl implements ReservationService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final OrdReservationMapper mapper;

    public ReservationServiceImpl(OrdReservationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<Reservation> page(Integer page, Integer size, String keyword, String status, String type) {
        LambdaQueryWrapper<OrdReservation> w = new LambdaQueryWrapper<>();
        if (OrderSupport.has(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(OrdReservation::getReservationNo, kw)
                    .or().like(OrdReservation::getCUserNo, kw)
                    .or().like(OrdReservation::getSiteName, kw)
                    .or().like(OrdReservation::getCabinetNo, kw));
        }
        if (OrderSupport.has(status)) w.eq(OrdReservation::getStatus, status);
        if (OrderSupport.has(type)) w.eq(OrdReservation::getType, type);
        w.orderByDesc(OrdReservation::getId);

        Page<OrdReservation> r = mapper.selectPage(new Page<>(OrderSupport.page(page), OrderSupport.size(size)), w);
        List<Reservation> rows = r.getRecords().stream().map(ReservationServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public OkResult cancel(String reservationNo) {
        OrdReservation e = selectByNo(reservationNo);
        if (e == null) throw new IllegalArgumentException("预约不存在: " + reservationNo);
        if (!STATUS_PENDING.equals(e.getStatus())) {
            // 服务端复校（[api/README §4.1] 明确要求）：只有 PENDING 可取消
            throw ServerException.of(ErrorCode.CONFLICT, "仅 PENDING 预约可取消: " + reservationNo + " → " + e.getStatus());
        }
        e.setStatus(STATUS_CANCELLED);
        mapper.updateById(e);
        return new OkResult(true);
    }

    private OrdReservation selectByNo(String no) {
        if (!OrderSupport.has(no)) return null;
        return mapper.selectOne(new LambdaQueryWrapper<OrdReservation>()
                .eq(OrdReservation::getReservationNo, no).last("limit 1"));
    }

    private static Reservation toVO(OrdReservation e) {
        return new Reservation(e.getReservationNo(), e.getCUserNo(), e.getType(), e.getSiteNo(),
                e.getSiteName(), e.getCabinetNo(), e.getReservedFrom(), e.getReservedTo(),
                e.getHoldFee(), e.getCurrency(), e.getStatus(), e.getOrderNo());
    }
}
