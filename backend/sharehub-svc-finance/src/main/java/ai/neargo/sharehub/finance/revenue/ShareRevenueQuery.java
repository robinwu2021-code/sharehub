package ai.neargo.sharehub.finance.revenue;

import ai.neargo.sharehub.finance.SettlementStatus;
import ai.neargo.sharehub.finance.ShareDimension;
import ai.neargo.sharehub.finance.ShareRecordStatus;
import ai.neargo.sharehub.finance.WithdrawalStatus;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.entity.StlSettlement;
import ai.neargo.sharehub.finance.entity.StlWithdrawal;
import ai.neargo.sharehub.finance.mapper.ShareRecordMapper;
import ai.neargo.sharehub.finance.mapper.StlSettlementMapper;
import ai.neargo.sharehub.finance.mapper.StlWithdrawalMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 商家营收的财务侧口径（需求方案 §10.1，商家一期 B5）。
 *
 * <ul>
 *   <li><b>税前营收</b> = 窗口内生成的商家分成明细的基数合计（V155 起为税前；V155 之前的明细取实收）；</li>
 *   <li><b>我的分成</b> = 同一批明细的分成金额合计；</li>
 *   <li><b>钱的状态</b>不按窗口，按现在：待结算（明细未进结算单）· 已结未付（结算单已确认、尚未到账）· 已到账（提现单已到账）。</li>
 * </ul>
 *
 * <p>只看商家（AGENT）一支 —— 一期场地方不分成（D10）。不写 where agent_no：四张资金表都登记了商家数据范围（V58）。
 */
@Service
public class ShareRevenueQuery {

    private static final String AGENT = ShareDimension.AGENT.name();

    private final ShareRecordMapper records;
    private final StlSettlementMapper settlements;
    private final StlWithdrawalMapper withdrawals;

    public ShareRevenueQuery(ShareRecordMapper records, StlSettlementMapper settlements, StlWithdrawalMapper withdrawals) {
        this.records = records;
        this.settlements = settlements;
        this.withdrawals = withdrawals;
    }

    public Shares shares(LocalDate from, LocalDate to) {
        Map<String, Object> m = first(records.selectMaps(new QueryWrapper<ShareRecord>()
                .select("COALESCE(SUM(COALESCE(base_amount, gross_amount)), 0) AS base", "COALESCE(SUM(amount), 0) AS share")
                .eq("payee_type", AGENT)
                .ge("created_at", from.atStartOfDay()).lt("created_at", to.plusDays(1).atStartOfDay())));
        return new Shares(dec(m.get("base")), dec(m.get("share")));
    }

    public Money money() {
        BigDecimal pending = dec(first(records.selectMaps(new QueryWrapper<ShareRecord>()
                .select("COALESCE(SUM(amount), 0) AS v").eq("payee_type", AGENT)
                .eq("status", ShareRecordStatus.PENDING.name()))).get("v"));
        BigDecimal settledUnpaid = dec(first(settlements.selectMaps(new QueryWrapper<StlSettlement>()
                .select("COALESCE(SUM(total_amount), 0) AS v").eq("payee_type", AGENT)
                .eq("status", SettlementStatus.CONFIRMED.name()))).get("v"));
        BigDecimal paid = dec(first(withdrawals.selectMaps(new QueryWrapper<StlWithdrawal>()
                .select("COALESCE(SUM(amount), 0) AS v").eq("payee_type", AGENT)
                .eq("status", WithdrawalStatus.PAID.name()))).get("v"));
        return new Money(pending, settledUnpaid, paid);
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() || rows.get(0) == null ? Map.of() : rows.get(0);
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
    }

    public record Shares(BigDecimal preTax, BigDecimal myShare) {
    }

    public record Money(BigDecimal pending, BigDecimal settledUnpaid, BigDecimal paid) {
    }
}
