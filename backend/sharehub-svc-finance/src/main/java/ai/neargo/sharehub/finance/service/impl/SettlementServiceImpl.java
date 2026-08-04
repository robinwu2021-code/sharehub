package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.SettlementStateMachine;
import ai.neargo.sharehub.finance.dto.FinDtos.Settlement;
import ai.neargo.sharehub.finance.dto.FinDtos.SettlementDetail;
import ai.neargo.sharehub.finance.dto.FinDtos.SettlementView;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.entity.StlSettlement;
import ai.neargo.sharehub.finance.entity.StlSettlementDetail;
import ai.neargo.sharehub.finance.mapper.ShareRecordMapper;
import ai.neargo.sharehub.finance.mapper.StlSettlementDetailMapper;
import ai.neargo.sharehub.finance.mapper.StlSettlementMapper;
import ai.neargo.sharehub.finance.service.SettlementService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算实现。出账口径：**把一个账期内待结算的分润记录，按收款方汇总成一张结算单**。
 *
 * <p>{@link #generate} 是唯一的创建路径（无人工入口），且必须可重跑 —— 批处理作业失败重试是常态，
 * 所以按 (payeeNo, period) 做了存在性检查，已出过账的主体直接跳过。
 */
@Service
public class SettlementServiceImpl implements SettlementService {

    private final StlSettlementMapper mapper;
    private final StlSettlementDetailMapper detailMapper;
    private final ShareRecordMapper recordMapper;
    private final SettlementStateMachine stateMachine;

    public SettlementServiceImpl(StlSettlementMapper mapper, StlSettlementDetailMapper detailMapper,
                                 ShareRecordMapper recordMapper, SettlementStateMachine stateMachine) {
        this.mapper = mapper;
        this.detailMapper = detailMapper;
        this.recordMapper = recordMapper;
        this.stateMachine = stateMachine;
    }

    @Override
    public ai.neargo.common.core.PageResult<Settlement> page(Integer page, Integer size,
                                                             String keyword, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);
        LambdaQueryWrapper<StlSettlement> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(StlSettlement::getSettleNo, keyword)
                    .or().like(StlSettlement::getPayeeNo, keyword)
                    .or().like(StlSettlement::getPayeeName, keyword));
        }
        w.eq(status != null && !status.isBlank(), StlSettlement::getStatus, status);
        w.orderByDesc(StlSettlement::getId);
        var r = mapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(p, s), w);
        // recordCount 是聚合不是列（db-design §1.4）：按本页 settle_no 批量 COUNT，避免 N+1
        java.util.List<String> nos = r.getRecords().stream().map(StlSettlement::getSettleNo).toList();
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        if (!nos.isEmpty()) {
            for (java.util.Map<String, Object> m : detailMapper.selectMaps(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StlSettlementDetail>()
                            .select("settle_no", "COUNT(*) AS cnt").in("settle_no", nos).groupBy("settle_no"))) {
                counts.put(String.valueOf(m.get("settle_no")), ((Number) m.get("cnt")).longValue());
            }
        }
        return new ai.neargo.common.core.PageResult<>(
                r.getRecords().stream()
                        .map(e -> toVO(e, counts.getOrDefault(e.getSettleNo(), 0L))).toList(),
                r.getTotal());
    }

    @Override
    public SettlementView detail(String settleNo) {
        StlSettlement e = require(settleNo);
        List<SettlementDetail> details = detailMapper.selectList(
                        new LambdaQueryWrapper<StlSettlementDetail>()
                                .eq(StlSettlementDetail::getSettleNo, settleNo)
                                .orderByAsc(StlSettlementDetail::getId))
                .stream()
                .map(d -> new SettlementDetail(d.getRefType(), d.getRefNo(), d.getAmount()))
                .toList();
        return new SettlementView(toVO(e), details);
    }

    @Override
    @Transactional
    public SettlementView confirm(String settleNo) {
        StlSettlement e = require(settleNo);
        e.setStatus(stateMachine.next(e.getStatus(), "CONFIRM")); // 非法迁移由状态机拒
        // 确认留痕（V31）：谁确认的、何时确认 —— 出账合规字段，服务端回填不信前端
        e.setConfirmedBy(ai.neargo.sharehub.auth.SecurityUtils.currentUser()
                .map(ai.neargo.sharehub.auth.LoginUser::username).orElse("system"));
        e.setConfirmedAt(java.time.LocalDateTime.now());
        mapper.updateById(e);
        return detail(settleNo);
    }

    @Override
    @Transactional
    public List<String> generate(String period, String payeeType) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("出账账期 period 必填（YYYY-MM）");
        }

        QueryWrapper<ShareRecord> w = new QueryWrapper<>();
        w.eq("status", "PENDING");
        // 按归属账期列取（V34）：创建时刻不等于归属周期，且函数包列无法走索引
        w.eq("period", period);
        if (payeeType != null && !payeeType.isBlank()) w.eq("payee_type", payeeType);
        List<ShareRecord> pending = recordMapper.selectList(w);

        // 按收款方分组（LinkedHashMap 保出账顺序稳定，便于重跑比对）
        Map<String, List<ShareRecord>> byPayee = new LinkedHashMap<>();
        for (ShareRecord r : pending) {
            byPayee.computeIfAbsent(r.getPayeeNo(), k -> new ArrayList<>()).add(r);
        }

        String tenantId = SecurityUtils.tenantId();
        List<String> created = new ArrayList<>();

        for (Map.Entry<String, List<ShareRecord>> en : byPayee.entrySet()) {
            String payeeNo = en.getKey();
            List<ShareRecord> group = en.getValue();

            // 幂等：同一 (payeeNo, period) 已出过账则跳过，批处理重跑不会出重单
            Long exists = mapper.selectCount(new LambdaQueryWrapper<StlSettlement>()
                    .eq(StlSettlement::getPayeeNo, payeeNo)
                    .eq(StlSettlement::getPeriod, period));
            if (exists != null && exists > 0) continue;

            BigDecimal total = group.stream()
                    .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            ShareRecord head = group.get(0);
            StlSettlement s = new StlSettlement();
            s.setTenantId(tenantId);
            s.setSettleNo(FinNos.nextNo(mapper, "settle_no", BizKey.SETTLEMENT, 6));
            s.setPayeeType(head.getPayeeType() != null ? head.getPayeeType() : head.getDimension());
            s.setPayeeNo(payeeNo);
            s.setPayeeName(head.getPayeeName());
            s.setPeriod(period);
            s.setTotalAmount(total);
            s.setCurrency(head.getCurrency());
            s.setStatus("GEN");
            mapper.insert(s);

            for (ShareRecord r : group) {
                StlSettlementDetail d = new StlSettlementDetail();
                d.setTenantId(tenantId);
                d.setSettleNo(s.getSettleNo());
                d.setRefType("SHARE"); // 明细指向分润记录，不是订单——一单可能有多条分润
                d.setRefNo(r.getRecordNo());
                d.setAmount(r.getAmount());
                detailMapper.insert(d);

                // 回填结算号并置 DONE：下一次出账就不会再把它捞进来（这是防重复结算的第二道闸）
                r.setSettleNo(s.getSettleNo());
                r.setStatus("DONE");
                recordMapper.updateById(r);
            }
            created.add(s.getSettleNo());
        }
        return created;
    }

    private StlSettlement require(String settleNo) {
        StlSettlement e = mapper.selectOne(new LambdaQueryWrapper<StlSettlement>()
                .eq(StlSettlement::getSettleNo, settleNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("结算单不存在: " + settleNo);
        return e;
    }

    private static Settlement toVO(StlSettlement e) {
        return toVO(e, null);
    }

    private static Settlement toVO(StlSettlement e, Long recordCount) {
        return new Settlement(e.getSettleNo(), e.getPayeeType(), e.getPayeeNo(), e.getPayeeName(),
                e.getPeriod(), e.getTotalAmount(), e.getCurrency(), e.getStatus(),
                e.getConfirmedBy(),
                e.getConfirmedAt() == null ? null : e.getConfirmedAt().toString(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
                recordCount);
    }
}
