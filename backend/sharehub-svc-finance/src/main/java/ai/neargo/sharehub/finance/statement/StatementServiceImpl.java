package ai.neargo.sharehub.finance.statement;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.finance.SettlementRefType;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.entity.StlAdjustment;
import ai.neargo.sharehub.finance.entity.StlSettlement;
import ai.neargo.sharehub.finance.entity.StlSettlementDetail;
import ai.neargo.sharehub.finance.mapper.ShareRecordMapper;
import ai.neargo.sharehub.finance.mapper.StlAdjustmentMapper;
import ai.neargo.sharehub.finance.mapper.StlSettlementDetailMapper;
import ai.neargo.sharehub.finance.mapper.StlSettlementMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账单实现。数据全部来自结算单明细（出账那一刻定格的分润记录与调整项），不重新计算 ——
 * 对账单要和已经发出去的钱逐分对得上，重算只会引入「规则后来改了」的差异。
 */
@Service
public class StatementServiceImpl implements StatementService {

    private final StlSettlementMapper settlements;
    private final StlSettlementDetailMapper details;
    private final ShareRecordMapper shares;
    private final StlAdjustmentMapper adjustments;

    public StatementServiceImpl(StlSettlementMapper settlements, StlSettlementDetailMapper details, ShareRecordMapper shares,
                                StlAdjustmentMapper adjustments) {
        this.settlements = settlements;
        this.details = details;
        this.shares = shares;
        this.adjustments = adjustments;
    }

    @Override
    public Statement statement(String settleNo) {
        StlSettlement s = settlements.selectOne(new LambdaQueryWrapper<StlSettlement>().eq(StlSettlement::getSettleNo, settleNo).last("limit 1"));
        if (s == null) throw BizException.notFound(settleNo);
        List<StlSettlementDetail> rows = details.selectList(new LambdaQueryWrapper<StlSettlementDetail>()
                .eq(StlSettlementDetail::getSettleNo, settleNo).orderByAsc(StlSettlementDetail::getId));
        List<String> shareNos = rows.stream().filter(d -> SettlementRefType.SHARE.name().equals(d.getRefType())).map(StlSettlementDetail::getRefNo).toList();
        List<String> adjNos = rows.stream().filter(d -> SettlementRefType.ADJUST.name().equals(d.getRefType())).map(StlSettlementDetail::getRefNo).toList();

        Map<String, ShareAcc> byKey = new LinkedHashMap<>();
        java.util.Set<String> orders = new java.util.HashSet<>();
        BigDecimal gross = BigDecimal.ZERO, shareTotal = BigDecimal.ZERO;
        if (!shareNos.isEmpty()) {
            for (ShareRecord r : shares.selectList(new LambdaQueryWrapper<ShareRecord>().in(ShareRecord::getRecordNo, shareNos))) {
                BigDecimal g = nz(r.getGrossAmount()), a = nz(r.getAmount());
                orders.add(r.getOrderNo());
                gross = gross.add(g);
                shareTotal = shareTotal.add(a);
                ShareAcc acc = byKey.computeIfAbsent(r.getSourceNo() + "|" + r.getRate(), k -> new ShareAcc(r.getSourceNo(), r.getRate()));
                acc.orders++;
                acc.gross = acc.gross.add(g);
                acc.amount = acc.amount.add(a);
            }
        }
        List<ShareLine> lines = byKey.values().stream().map(x -> new ShareLine(x.contractNo, x.rate, x.orders, x.gross, x.amount)).toList();
        List<AdjustLine> adj = new ArrayList<>();
        BigDecimal adjTotal = BigDecimal.ZERO;
        if (!adjNos.isEmpty()) {
            for (StlAdjustment a : adjustments.selectList(new LambdaQueryWrapper<StlAdjustment>().in(StlAdjustment::getAdjNo, adjNos))) {
                adj.add(new AdjustLine(a.getAdjNo(), a.getKind(), a.getContractNo(), a.getSiteNo(), a.getPeriod(), a.getAmount(), a.getNote()));
                adjTotal = adjTotal.add(nz(a.getAmount()));
            }
        }
        return new Statement(s.getSettleNo(), s.getPayeeType(), s.getPayeeNo(), s.getPayeeName(), s.getPeriod(), s.getCurrency(),
                s.getStatus(), orders.size(), gross, shareTotal, lines, adjTotal, adj, nz(s.getTotalAmount()));
    }

    @Override
    public String html(String settleNo, String lang) {
        return StatementHtml.render(statement(settleNo), lang);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static final class ShareAcc {
        final String contractNo;
        final BigDecimal rate;
        long orders;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;

        ShareAcc(String contractNo, BigDecimal rate) {
            this.contractNo = contractNo;
            this.rate = rate;
        }
    }
}
