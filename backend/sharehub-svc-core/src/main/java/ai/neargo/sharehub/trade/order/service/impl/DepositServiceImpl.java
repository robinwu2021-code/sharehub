package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.common.core.ServerException;
import ai.neargo.common.core.ErrorCode;
import ai.neargo.sharehub.trade.order.DepositStatus;

import ai.neargo.common.core.PageResult;
import org.springframework.transaction.annotation.Transactional;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.trade.order.dto.OrderDtos.DepositRecord;
import ai.neargo.sharehub.trade.order.entity.OrdDeposit;
import ai.neargo.sharehub.trade.order.mapper.OrdDepositMapper;
import ai.neargo.sharehub.trade.order.service.DepositService;
import ai.neargo.sharehub.trade.order.service.OrderEventLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 押金与欠费实现。
 *
 * <p>{@link #release} 只放行 {@code HELD} → {@code RELEASED}。
 * {@code BOUGHT_OUT}（钱已计收入）与 {@code ARREARS}（钱待追偿）都不可解冻 ——
 * 这两条放开就是把已确认的收入或应收直接抹掉。
 */
@Service
public class DepositServiceImpl implements DepositService {


    private final OrdDepositMapper mapper;
    private final OrderEventLogService eventLog;

    public DepositServiceImpl(OrdDepositMapper mapper, OrderEventLogService eventLog) {
        this.mapper = mapper;
        this.eventLog = eventLog;
    }

    @Override
    public PageResult<DepositRecord> page(Integer page, Integer size, String keyword, String status) {
        LambdaQueryWrapper<OrdDeposit> w = new LambdaQueryWrapper<>();
        if (OrderSupport.has(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(OrdDeposit::getDepositNo, kw)
                    .or().like(OrdDeposit::getOrderNo, kw)
                    .or().like(OrdDeposit::getCUserNo, kw));
        }
        if (OrderSupport.has(status)) w.eq(OrdDeposit::getStatus, status);
        w.orderByDesc(OrdDeposit::getId);

        Page<OrdDeposit> r = mapper.selectPage(new Page<>(OrderSupport.page(page), OrderSupport.size(size)), w);
        List<DepositRecord> rows = r.getRecords().stream().map(DepositServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public OkResult release(String depositNo) {
        OrdDeposit e = selectByNo(depositNo);
        if (e == null) throw new IllegalArgumentException("押金记录不存在: " + depositNo);
        if (!DepositStatus.HELD.is(e.getStatus())) {
            throw ServerException.of(ErrorCode.CONFLICT, "仅 HELD 押金可解冻: " + depositNo + " → " + e.getStatus());
        }
        e.setStatus(DepositStatus.RELEASED.name());
        e.setReleasedAt(OrderSupport.now());
        mapper.updateById(e);

        eventLog.append(e.getOrderNo(), DepositStatus.HELD.name(), DepositStatus.RELEASED.name(),
                "DEPOSIT_RELEASE", OrderSupport.currentName());
        return new OkResult(true);
    }

    private OrdDeposit selectByNo(String no) {
        if (!OrderSupport.has(no)) return null;
        return mapper.selectOne(new LambdaQueryWrapper<OrdDeposit>()
                .eq(OrdDeposit::getDepositNo, no).last("limit 1"));
    }

    private static DepositRecord toVO(OrdDeposit e) {
        return new DepositRecord(e.getDepositNo(), e.getOrderNo(), e.getCUserNo(), e.getAmount(),
                e.getCurrency(), e.getStatus(), e.getArrearsAmount(), e.getReleasedAt(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
                e.getBuyoutAmount(),
                e.getBuyoutAt() == null ? null : e.getBuyoutAt().toString(),
                e.getDunCount(),
                e.getLastDunAt() == null ? null : e.getLastDunAt().toString(),
                e.getLastDunChannel(), e.getOperatorName(), e.getNote());
    }

    @Override
    @Transactional
    public DepositRecord buyout(String depositNo, String note) {
        OrdDeposit e = require(depositNo);
        if (DepositStatus.RELEASED.is(e.getStatus())) {
            throw ServerException.of(ErrorCode.CONFLICT, "押金已解冻，不能再买断: " + depositNo);
        }
        // 买断金额 = 押金全额。**不得超过押金额** —— 押金抵购机款，抵不了更多；
        // 差额部分若真要收，那是另一笔应收，不该混进押金单。
        e.setBuyoutAmount(e.getAmount());
        e.setBuyoutAt(java.time.LocalDateTime.now());
        // 词表是 BOUGHT_OUT（DDL / 实体 javadoc / 运营端联合类型都是它）——
        // 这里曾写成 "BUYOUT"，于是买断后的状态在运营端根本不在取值集里
        e.setStatus(DepositStatus.BOUGHT_OUT.name());
        e.setOperatorName(ai.neargo.sharehub.auth.SecurityUtils.currentUser()
                .map(ai.neargo.sharehub.auth.LoginUser::username).orElse(null));
        e.setNote(note);
        mapper.updateById(e);
        return toVO(require(depositNo));
    }

    @Override
    @Transactional
    public DepositRecord dun(String depositNo, String channel, String note) {
        OrdDeposit e = require(depositNo);
        // **催缴只留痕，不改状态** —— 催缴不改变欠款事实，改状态会让「已催缴」被误读成「已解决」。
        e.setDunCount((e.getDunCount() == null ? 0 : e.getDunCount()) + 1);
        e.setLastDunAt(java.time.LocalDateTime.now());
        e.setLastDunChannel(channel);
        e.setOperatorName(ai.neargo.sharehub.auth.SecurityUtils.currentUser()
                .map(ai.neargo.sharehub.auth.LoginUser::username).orElse(null));
        if (note != null && !note.isBlank()) e.setNote(note);
        mapper.updateById(e);
        return toVO(require(depositNo));
    }

    /** 按业务键取，取不到就抛 —— 前端不该调到不存在的行。 */
    private OrdDeposit require(String depositNo) {
        OrdDeposit e = mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrdDeposit>()
                        .eq(OrdDeposit::getDepositNo, depositNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("押金记录不存在: " + depositNo);
        return e;
    }
}
