package ai.neargo.sharehub.report.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.Kw;
import ai.neargo.sharehub.common.Pages;
import ai.neargo.sharehub.report.dto.ReportDtos.SiteAnalysis;
import ai.neargo.sharehub.report.service.SiteAnalysisService;
import ai.neargo.sharehub.report.dto.ReportDtos.SiteRollup;
import ai.neargo.sharehub.report.service.ReportService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 站点坪效读模型 —— **不再是空实现**：改为委托报表域的站点卷积（{@link ReportService#siteRollups}）。
 *
 * <p><b>本类原先的 TODO(读模型) 已由报表域落地，故删除</b>。原 TODO 提的两点都做到了：
 * ① 聚合走一条 GROUP BY SQL（{@code ReportMappers.OrderFactMapper#facts}），不在 Java 里拉全表；
 * ② {@code cabinetCount} 取 {@code COUNT(dev_cabinet)} 而非 {@code loc_site.cabinet_count} 列。
 *
 * <p><b>为什么委托而不是在本类里再写一遍 SQL</b>：本端点（{@code /api/ops/site-analysis}）与
 * 报表域的点位报表（{@code /api/ops/reports/location}）算的是同一件事 —— 站点的营收与坪效。
 * 两处各写一套聚合，页面上就会出现「站点坪效页 12.3 万、点位报表页 11.8 万」这种
 * 无法判断谁对的分歧。委托之后口径物理上只有一份。
 *
 * <p><b>⚠️ 唯一仍不可得的字段是 {@code paybackDays}（回本天数），恒返回 0</b>：它需要「累计投入」，
 * 而全站没有资产成本表（[db-design] 未建）。{@code loc_contract.entry_fee} 只是进场费，
 * 拿它单独摊会系统性低估回本期 —— 运营会拿这个数去拍撤站决策，
 * <b>给一个口径不对的数字比给空值更难纠错</b>，所以这里坚持留空（前端渲染成「—」）。
 * 补齐条件：新增单柜采购成本（或站点累计投入）列/表，接进 {@code SiteRollup} 后本类一行改动即可。
 */
@Service
public class SiteAnalysisServiceImpl implements SiteAnalysisService {

    /** 缺省窗口：近 30 天（与报表域 {@code LAST_30D} 同宽，避免两个入口默认口径不同）。 */
    private static final int DEFAULT_DAYS = 30;

    private final ReportService reportService;
    private final Clock clock;

    public SiteAnalysisServiceImpl(ReportService reportService, Clock clock) {
        this.reportService = reportService;
        this.clock = clock;
    }

    @Override
    public PageResult<SiteAnalysis> page(Integer page, Integer size, String keyword, String from, String to) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        // from/to 是「含端点的 UTC 业务日」，与报表域周期同口径。
        // 缺省 to 取**昨日**而非今日：今日是残日，与昨天并排看会莫名矮一截，被当成掉量。
        LocalDate end = parse(to, today.minusDays(1));
        LocalDate start = parse(from, end.minusDays(DEFAULT_DAYS - 1L));
        if (start.isAfter(end)) {
            start = end;
        }
        List<SiteAnalysis> rows = new ArrayList<>();
        for (SiteRollup r : reportService.siteRollups(start, end)) {
            rows.add(new SiteAnalysis(r.siteNo(), r.siteName(), r.revenue(), r.orders().intValue(),
                    turnover(r), 0, r.cabinetCount(), r.currency()));
        }
        return Pages.of(rows.stream().filter(x -> Kw.hit(keyword, x.siteNo(), x.siteName())).toList(),
                page, size);
    }

    /** 翻台率 = 订单数 / 天数 / 柜数（次/柜/日）。柜数 0 时按 1 兜底，避免除零把整页打成 500。 */
    private static BigDecimal turnover(SiteRollup r) {
        long denom = (long) Math.max(1, r.days()) * Math.max(1, r.cabinetCount());
        return BigDecimal.valueOf(r.orders()).divide(BigDecimal.valueOf(denom), 2, RoundingMode.HALF_UP);
    }

    private static LocalDate parse(String s, LocalDate fallback) {
        if (s == null || s.isBlank() || s.trim().length() < 10) {
            return fallback;
        }
        try {
            return LocalDate.parse(s.trim().substring(0, 10));
        } catch (RuntimeException e) {
            // 日期从查询参数来，旧书签带着脏值不该整页 500 —— 落回缺省窗口。
            return fallback;
        }
    }
}
