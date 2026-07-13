package ai.neargo.powerbank.trade.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.dto.Dto.OkResult;
import ai.neargo.powerbank.dto.Dto.RentOrder;
import ai.neargo.powerbank.dto.Dto.RentResult;
import ai.neargo.powerbank.trade.OrdStateMachine;
import ai.neargo.powerbank.trade.entity.OrdRent;
import ai.neargo.powerbank.trade.mapper.OrdMapper;
import ai.neargo.powerbank.trade.service.RentOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 租借订单业务实现。借还走独立状态机 {@link OrdStateMachine}；押金/支付/弹仓为骨架（ADR-005 委托 nearpay）。 */
@Service
public class RentOrderServiceImpl implements RentOrderService {

    private static final double DEPOSIT = 50.0;      // 免押额度（AED），骨架
    private static final String CURRENCY = "AED";

    private final OrdMapper mapper;
    private final OrdStateMachine stateMachine;

    public RentOrderServiceImpl(OrdMapper mapper, OrdStateMachine stateMachine) {
        this.mapper = mapper;
        this.stateMachine = stateMachine;
    }

    @Override
    public PageResult<RentOrder> pageAdmin(Integer page, Integer size, String keyword, String status) {
        int p = pg(page), s = sz(size);
        LambdaQueryWrapper<OrdRent> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(OrdRent::getOrderNo, keyword).or().like(OrdRent::getCUserNo, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(OrdRent::getStatus, status);
        w.orderByDesc(OrdRent::getId);
        Page<OrdRent> r = mapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(RentOrderServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public PageResult<RentOrder> pageByOwner(String cUserNo, Integer page, Integer size) {
        int p = pg(page), s = sz(size);
        LambdaQueryWrapper<OrdRent> w = new LambdaQueryWrapper<OrdRent>()
                .eq(OrdRent::getCUserNo, cUserNo).orderByDesc(OrdRent::getId);
        Page<OrdRent> r = mapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(RentOrderServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public RentOrder detail(String orderNo) {
        return toVO(require(orderNo));
    }

    @Override
    public RentResult rent(String cUserNo, String cabinetNo) {
        if (cabinetNo == null || cabinetNo.isBlank()) throw new IllegalArgumentException("柜机号为空");
        OrdRent e = new OrdRent();
        e.setOrderNo(IdGenerator.next("ORD"));
        e.setCUserNo(cUserNo);
        e.setCabinetNo(cabinetNo);
        e.setPowerbankNo(IdGenerator.next("PB"));
        e.setStatus("IN_USE");                       // 免押预授权 + 弹仓为骨架，直接置借用中
        e.setRentStartAt(Instant.now().toString());
        e.setDepositAmount(DEPOSIT);
        e.setFeeAmount(0.0);
        e.setCurrency(CURRENCY);
        e.setTenantId("MAIN");
        mapper.insert(e);
        return new RentResult(e.getOrderNo(), e.getPowerbankNo(), "CMD" + System.nanoTime());
    }

    @Override
    public OkResult returnOrder(String orderNo, String returnCabinetNo) {
        OrdRent e = require(orderNo);
        e.setStatus(stateMachine.next(e.getStatus(), "RETURN"));   // IN_USE→RETURNED，非法迁移拒
        e.setReturnCabinetNo(returnCabinetNo);
        String endAt = Instant.now().toString();
        e.setRentEndAt(endAt);
        long min = durationMinutes(e.getRentStartAt(), endAt);
        e.setDurationMin((int) min);
        e.setFeeAmount(fee(min));
        e.setStatus(stateMachine.next(e.getStatus(), "SETTLE"));   // RETURNED→SETTLED（支付为骨架）
        mapper.updateById(e);
        return new OkResult(true);
    }

    @Override
    public OkResult intervene(String orderNo, String action) {
        OrdRent e = require(orderNo);
        e.setStatus("CLOSED");                        // 强制关单；免单/补偿为骨架
        mapper.updateById(e);
        return new OkResult(true);
    }

    // —— 计费（骨架：每 30 分钟 3 AED，封顶 30）——
    private static double fee(long minutes) {
        return Math.min(30.0, Math.ceil(minutes / 30.0) * 3.0);
    }

    private static long durationMinutes(String startIso, String endIso) {
        try {
            return Math.max(0, Duration.between(Instant.parse(startIso), Instant.parse(endIso)).toMinutes());
        } catch (Exception ex) {
            return 0;
        }
    }

    private OrdRent require(String orderNo) {
        OrdRent e = mapper.selectOne(new LambdaQueryWrapper<OrdRent>().eq(OrdRent::getOrderNo, orderNo));
        if (e == null) throw new IllegalArgumentException("订单不存在: " + orderNo);
        return e;
    }

    private static int pg(Integer page) { return (page == null || page < 1) ? 1 : page; }
    private static int sz(Integer size) { return (size == null || size < 1) ? 10 : size; }

    private static RentOrder toVO(OrdRent e) {
        return new RentOrder(e.getOrderNo(), e.getCUserNo(), e.getCabinetNo(), e.getReturnCabinetNo(),
                e.getPowerbankNo(), e.getLocationName(), e.getStatus(), e.getRentStartAt(), e.getRentEndAt(),
                e.getDurationMin(), e.getFeeAmount() == null ? 0 : e.getFeeAmount(),
                e.getDepositAmount() == null ? 0 : e.getDepositAmount(), e.getCurrency());
    }
}
