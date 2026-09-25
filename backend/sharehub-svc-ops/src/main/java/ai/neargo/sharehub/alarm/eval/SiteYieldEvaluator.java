package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;
import ai.neargo.sharehub.alarm.mapper.DevAlarmCodeMapper;
import ai.neargo.sharehub.api.core.port.CabinetStatePort;
import ai.neargo.sharehub.api.core.port.OrderQueryPort;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 低效站点（对齐清单 G1 · E18）：营业中的站点，连续 N 个整月（默认 2）单柜日均收入都低于告警码阈值（AED，默认 5）
 * → BD 待办：迁机或撤场评估。上线不满 N 个月的不判 —— 新站点的爬坡期不是低效。
 *
 * <p>单柜日均 = 当月已结算收入 ÷（当前已布放柜数 × 当月天数）。柜数取当前值是近似：月中加减柜会有偏差，
 * 但这条告警要回答的是「这里值不值得继续放柜子」，量级对就够了。
 */
@Component
public class SiteYieldEvaluator implements StateEvaluator {

    public static final String SITE_LOW_YIELD = "SITE_LOW_YIELD";
    private static final Set<String> DEPLOYED = Set.of("DEPLOYED");

    private final OrderQueryPort orders;
    private final CabinetStatePort cabinets;
    private final DevAlarmCodeMapper codes;
    private final SysParamPort params;

    public SiteYieldEvaluator(OrderQueryPort orders, CabinetStatePort cabinets, DevAlarmCodeMapper codes, SysParamPort params) {
        this.orders = orders;
        this.cabinets = cabinets;
        this.codes = codes;
        this.params = params;
    }

    @Override
    public Set<String> codes() {
        return Set.of(SITE_LOW_YIELD);
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        int months = Math.max(1, params.intOf("site.low_yield.months", 2));
        YearMonth last = YearMonth.from(now).minusMonths(1);
        YearMonth first = last.minusMonths(months - 1L);
        List<SiteBrief> sites = scope.sites().stream().filter(SiteBrief::rentable)
                .filter(s -> s.firstLiveOn() != null && !s.firstLiveOn().isAfter(first.atDay(1))).toList();
        if (sites.isEmpty()) return List.of();
        DevAlarmCode c = codes.selectOne(new LambdaQueryWrapper<DevAlarmCode>().eq(DevAlarmCode::getCode, SITE_LOW_YIELD).last("limit 1"));
        BigDecimal line = c == null || c.getThreshold() == null ? new BigDecimal("5") : c.getThreshold();
        List<String> nos = sites.stream().map(SiteBrief::siteNo).toList();
        Map<String, Long> cabs = cabinets.countBySites(nos, DEPLOYED);
        Map<String, Map<String, BigDecimal>> gmv = orders.monthlyGmvBySites(nos, first.atDay(1), last.plusMonths(1).atDay(1));
        List<Finding> out = new ArrayList<>();
        for (SiteBrief s : sites) {
            long n = cabs.getOrDefault(s.siteNo(), 0L);
            if (n == 0) continue;   // 没有柜子谈不上单柜效益（那是撤场 / 装机的事）
            List<Finding.Evidence> ev = new ArrayList<>();
            boolean allBelow = true;
            for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
                BigDecimal g = gmv.getOrDefault(s.siteNo(), Map.of()).getOrDefault(m.toString(), BigDecimal.ZERO);
                BigDecimal daily = g.divide(BigDecimal.valueOf(n * m.lengthOfMonth()), 2, RoundingMode.HALF_UP);
                if (daily.compareTo(line) >= 0) {
                    allBelow = false;
                    break;
                }
                ev.add(new Finding.Evidence("YIELD", null, null, now, m + " 单柜日均 " + daily + "（" + n + " 台，线 " + line + "）"));
            }
            if (!allBelow) continue;
            out.add(new Finding(SITE_LOW_YIELD, AlarmSubjectType.SITE, s.siteNo(), s.siteNo(), null, s.agentNo(), s.regionId(),
                    AlarmCause.LOW_YIELD, ImpactScope.SITE, 0, ev, Map.of()));
        }
        return out;
    }
}
