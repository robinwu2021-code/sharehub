package ai.neargo.sharehub.report.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.Kw;
import ai.neargo.sharehub.common.Pages;
import ai.neargo.sharehub.report.ReportPeriods;
import ai.neargo.sharehub.report.ReportPeriods.Bucket;
import ai.neargo.sharehub.report.ReportPeriods.Period;
import ai.neargo.sharehub.report.dto.ReportDtos.ConsumerFunnelStage;
import ai.neargo.sharehub.report.dto.ReportDtos.ConsumerInsight;
import ai.neargo.sharehub.report.dto.ReportDtos.ConsumerProfileSlice;
import ai.neargo.sharehub.report.dto.ReportDtos.ConsumerSegment;
import ai.neargo.sharehub.report.dto.ReportDtos.DashboardAlert;
import ai.neargo.sharehub.report.dto.ReportDtos.DashboardRankItem;
import ai.neargo.sharehub.report.dto.ReportDtos.DashboardStats;
import ai.neargo.sharehub.report.dto.ReportDtos.DashboardTodos;
import ai.neargo.sharehub.report.dto.ReportDtos.DashboardTrendDay;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportCustom;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportDevice;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportFinance;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportLocation;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportMetricDef;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportScreen;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportSummaryItem;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportTrend;
import ai.neargo.sharehub.report.dto.ReportDtos.ReportTrendPoint;
import ai.neargo.sharehub.report.dto.ReportDtos.ScreenBoard;
import ai.neargo.sharehub.report.dto.ReportDtos.ScreenBoardPoint;
import ai.neargo.sharehub.report.dto.ReportDtos.ScreenRankRow;
import ai.neargo.sharehub.report.dto.ReportDtos.ScreenStatusSlice;
import ai.neargo.sharehub.report.dto.ReportDtos.SiteRollup;
import ai.neargo.sharehub.report.mapper.ReportMappers;
import ai.neargo.sharehub.report.mapper.ReportMappers.CabinetFactMapper;
import ai.neargo.sharehub.report.mapper.ReportMappers.ContractRateMapper;
import ai.neargo.sharehub.report.mapper.ReportMappers.OrderFactMapper;
import ai.neargo.sharehub.report.mapper.ReportMappers.ShareFactMapper;
import ai.neargo.sharehub.report.mapper.ReportMappers.WoFactMapper;
import ai.neargo.sharehub.report.service.ReportService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 报表域读模型实现。
 *
 * <h2>为什么是「一份事实 → 多种折法」而不是每张报表各查一遍</h2>
 * 整域只有一个事实来源：{@code OrderFactMapper.facts()} 返回的 (业务日, 小时, 站点名) → 单量/GMV。
 * 表格按站点折、图表按桶折、汇总条按全周期折、大屏按小时折。<b>因此「表格合计 = 折线合计 = 汇总条」
 * 是结构性成立的</b>，不需要靠约定或人工核对。反面案例就在 ops-web 改造前：表格与折线各造一份序列，
 * 出现「表格 12 行合计 80 万、折线合计 60 万」这种一眼假的画面。
 *
 * <h2>⚠️ 口径空洞清单（不要为了「表格好看」把它们填成近似值）</h2>
 * <table>
 *   <tr><td>回本天数 payback</td><td>恒 0</td>
 *       <td>缺「单柜采购成本」——全站无资产成本表。{@code loc_contract.entry_fee}（进场费）
 *           只是投入的一部分，单独摊会系统性低估回本期，故不用。</td></tr>
 *   <tr><td>已结算 settle</td><td>真实 0</td>
 *       <td>{@code share_record} 无 seeder、结算批处理未在开发库跑过。用「应付分润」顶替它
 *           就是伪造财务数字。</td></tr>
 *   <tr><td>在线率 / 故障率</td><td>当前快照</td>
 *       <td>{@code dev_heartbeat} 空、无按日在线快照表，无法算「周期内日均」。
 *           <b>后果：切周期这两个数不变</b>，这是已知偏差而不是缓存 bug。</td></tr>
 *   <tr><td>运维成本 opex</td><td>不计入</td>
 *       <td>全站无表，故 {@code cost} 仅含场地分润一项，{@code net = gmv − share}。</td></tr>
 *   <tr><td>在借充电宝</td><td>用 IN_USE 订单数</td>
 *       <td>{@code dev_powerbank} 表为空，无 RENTED 行可数；一单一宝，口径 1:1 等价。</td></tr>
 *   <tr><td>画像 年龄段 / 终端</td><td>不返回</td>
 *       <td>{@code usr_user} 无出生日期列；{@code usr_identity.provider} 是登录方式不是终端，
 *           且只覆盖 8/41 个下单用户，凑进去会破坏「同维 Σ = totalUsers」。</td></tr>
 *   <tr><td>漏斗 扫码进入 / 授权登录</td><td>不返回</td>
 *       <td>无埋点/事件表。反推上游环节需要一组假定转化率，那是编数字。</td></tr>
 * </table>
 *
 * <h2>成本口径只有一处定义</h2>
 * 分润 = {@code Σ 逐条事实(gmv × 该站点生效合约 share_rate)}，四舍五入到分**在逐条事实上完成**。
 * 这样无论按站点折还是按桶折，加总必然相等（先折后乘会因舍入产生分位差，测试断言就会飘）。
 * 点位报表的 cost、财务报表的 share、自定义报表的 COST/NET 全部走这一个算子。
 */
@Service
public class ReportServiceImpl implements ReportService {

    /** 无订单/无合约时的兜底币种。多币种运营时应改为按站点取，届时 currency 要进 SiteRollup 分组键。 */
    private static final String FALLBACK_CURRENCY = "AED";

    private static final String UNASSIGNED_SCENE = "未分类";

    /** 指标目录：与 ops-web {@code REPORT_METRICS} 同源同序。少同步一处就会「勾了指标但表里没这列」。 */
    private static final List<ReportMetricDef> METRICS = List.of(
            new ReportMetricDef("GMV", "GMV", "MONEY"),
            new ReportMetricDef("ORDERS", "订单数", "NUMBER"),
            new ReportMetricDef("AOV", "客单价", "MONEY"),
            new ReportMetricDef("COST", "成本", "MONEY"),
            new ReportMetricDef("NET", "净收入", "MONEY"),
            new ReportMetricDef("ONLINE_RATE", "在线率", "RATE"),
            new ReportMetricDef("TURNOVER", "翻台率", "NUMBER"),
            new ReportMetricDef("FAULT_RATE", "故障率", "RATE"));

    private static final List<String> METRICS_DEFAULT = List.of("GMV", "ORDERS", "AOV");

    private final OrderFactMapper orderFacts;
    private final CabinetFactMapper cabinetFacts;
    private final ContractRateMapper contractRates;
    private final ShareFactMapper shareFacts;
    private final WoFactMapper woFacts;
    private final ReportMappers.RefundFactMapper refundFacts;
    private final ReportMappers.WithdrawFactMapper withdrawFacts;
    private final ReportMappers.AlarmFactMapper alarmFacts;
    private final Clock clock;

    /**
     * {@code Clock} 注入而非 {@code LocalDate.now()} 直调：报表的全部口径都挂在「今日」上，
     * 不可注入的话「周期统计到昨日」这条约定就无法被测试钉住（只能睡到明天再跑）。
     * 生产由 Boot 提供 {@code Clock.systemUTC()}（见 {@code ReportClockConfig}）。
     */
    public ReportServiceImpl(OrderFactMapper orderFacts,
                            CabinetFactMapper cabinetFacts,
                            ContractRateMapper contractRates,
                            ShareFactMapper shareFacts,
                            WoFactMapper woFacts,
                            ReportMappers.RefundFactMapper refundFacts,
                            ReportMappers.WithdrawFactMapper withdrawFacts,
                            ReportMappers.AlarmFactMapper alarmFacts,
                            Clock clock) {
        this.orderFacts = orderFacts;
        this.cabinetFacts = cabinetFacts;
        this.contractRates = contractRates;
        this.shareFacts = shareFacts;
        this.woFacts = woFacts;
        this.refundFacts = refundFacts;
        this.withdrawFacts = withdrawFacts;
        this.alarmFacts = alarmFacts;
        this.clock = clock;
    }

    // ————————————————————————————————————————————————————————————
    // 事实层
    // ————————————————————————————————————————————————————————————

    /** 一条订单事实。{@code share} 在装载时就按站点费率算好并定格到分，保证任何折法加总一致。 */
    private record Fact(LocalDate date, int hour, String siteName, long orders,
                        BigDecimal gmv, BigDecimal share, String currency) {
    }

    /** 机柜快照一行（站点名维度）。 */
    private record Snap(String siteName, String siteNo, String sceneType,
                        int cabinetCount, int onlineCount, int faultCount) {
    }

    /** 一次请求内的事实集合 + 主数据快照。构造即查库，之后只在内存折叠。 */
    private final class Facts {
        private final List<Fact> rows;
        private final Map<String, Snap> snaps;

        Facts(LocalDate from, LocalDate to) {
            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            for (Map<String, Object> r : contractRates.activeRates()) {
                rates.put(str(r.get("siteName")), rate4(r.get("shareRate")));
            }
            this.snaps = new LinkedHashMap<>();
            for (Map<String, Object> r : cabinetFacts.snapshot()) {
                String name = str(r.get("siteName"));
                snaps.put(name, new Snap(name, str(r.get("siteNo")),
                        blankTo(str(r.get("sceneType")), UNASSIGNED_SCENE),
                        i(r.get("cabinetCount")), i(r.get("onlineCount")), i(r.get("faultCount"))));
            }
            this.rows = new ArrayList<>();
            for (Map<String, Object> r : orderFacts.facts(ReportPeriods.fmt(from), ReportPeriods.fmt(to))) {
                String name = str(r.get("siteName"));
                BigDecimal gmv = money(r.get("gmv"));
                BigDecimal shareRate = rates.getOrDefault(name, BigDecimal.ZERO);
                rows.add(new Fact(LocalDate.parse(str(r.get("bizDate"))), i(r.get("bizHour")), name,
                        l(r.get("orders")), gmv, money(gmv.multiply(shareRate)),
                        blankTo(str(r.get("currency")), FALLBACK_CURRENCY)));
            }
        }

        /** 报表行集合 = 有机柜的站点 ∪ 有订单的站点。少了后者会丢掉「柜子已撤但周期内有单」的站点营收。 */
        Set<String> siteNames() {
            Set<String> names = new LinkedHashSet<>(snaps.keySet());
            rows.forEach(f -> names.add(f.siteName()));
            return names;
        }

        String currency() {
            return rows.stream().map(Fact::currency).findFirst().orElse(FALLBACK_CURRENCY);
        }

        Snap snap(String siteName) {
            return snaps.getOrDefault(siteName, new Snap(siteName, "", UNASSIGNED_SCENE, 0, 0, 0));
        }

        Agg agg(Predicate<Fact> keep, Set<String> siteNames, int days) {
            Agg a = new Agg(days);
            rows.stream().filter(f -> siteNames.contains(f.siteName())).filter(keep).forEach(a::add);
            for (String n : siteNames) {
                Snap s = snap(n);
                a.cabinets += s.cabinetCount();
                a.online += s.onlineCount();
                a.faults += s.faultCount();
            }
            return a;
        }
    }

    /** 折叠累加器。加法只发生在这里 —— 每张报表各写一遍求和是口径漂移的主要来源。 */
    private static final class Agg {
        private final int days;
        private long orders;
        private BigDecimal gmv = BigDecimal.ZERO;
        private BigDecimal share = BigDecimal.ZERO;
        private int cabinets;
        private int online;
        private int faults;

        Agg(int days) {
            this.days = Math.max(1, days);
        }

        void add(Fact f) {
            orders += f.orders();
            gmv = gmv.add(f.gmv());
            share = share.add(f.share());
        }

        BigDecimal gmv() {
            return money(gmv);
        }

        /** 成本 = 场地分润。opex 无表，故不加第二项（见类注释空洞清单）。 */
        BigDecimal cost() {
            return money(share);
        }

        BigDecimal net() {
            return money(gmv.subtract(share));
        }

        /** ROI = 毛利 / 成本；成本为 0（该站点无生效合约）时返回 0 表示「不可得」，不返回 ∞。 */
        BigDecimal roi() {
            return cost().signum() == 0 ? BigDecimal.ZERO
                    : gmv.subtract(share).divide(cost(), 2, RoundingMode.HALF_UP);
        }

        BigDecimal turnover() {
            return BigDecimal.valueOf(orders)
                    .divide(BigDecimal.valueOf((long) days * Math.max(1, cabinets)), 2, RoundingMode.HALF_UP);
        }

        double onlineRate() {
            return cabinets == 0 ? 0d : rate(online / (double) cabinets);
        }

        double faultRate() {
            return cabinets == 0 ? 0d : rate(faults / (double) cabinets);
        }

        BigDecimal aov() {
            return orders == 0 ? BigDecimal.ZERO
                    : gmv.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** 周期窗口：{@code [today − days, today − 1]}，即**统计到昨日**（T+1）。 */
    private Facts periodFacts(Period p) {
        return new Facts(ReportPeriods.from(p, today()), ReportPeriods.to(today()));
    }

    // ————————————————————————————————————————————————————————————
    // 三张周期报表
    // ————————————————————————————————————————————————————————————

    @Override
    public PageResult<ReportDevice> device(Integer page, Integer size, String keyword, String period) {
        Period p = ReportPeriods.normalize(period);
        Facts f = periodFacts(p);
        List<ReportDevice> rows = new ArrayList<>();
        for (String name : sorted(f)) {
            Agg a = f.agg(x -> true, Set.of(name), p.days());
            rows.add(new ReportDevice(name, a.onlineRate(), a.turnover(), a.faultRate(),
                    a.cabinets, a.orders));
        }
        return Pages.of(rows.stream().filter(r -> Kw.hit(keyword, r.locationName())).toList(), page, size);
    }

    @Override
    public PageResult<ReportLocation> location(Integer page, Integer size, String keyword, String period) {
        Period p = ReportPeriods.normalize(period);
        Facts f = periodFacts(p);
        String currency = f.currency();
        List<ReportLocation> rows = new ArrayList<>();
        for (String name : sorted(f)) {
            Agg a = f.agg(x -> true, Set.of(name), p.days());
            // payback 恒 0：缺单柜采购成本，见类注释。前端把 0 渲染成「—」，不会被误读成「当天回本」。
            rows.add(new ReportLocation(name, a.gmv(), a.cost(), 0, a.roi(), a.orders, currency));
        }
        return Pages.of(rows.stream().filter(r -> Kw.hit(keyword, r.siteName())).toList(), page, size);
    }

    @Override
    public PageResult<ReportFinance> finance(Integer page, Integer size, String keyword, String period) {
        Period p = ReportPeriods.normalize(period);
        Facts f = periodFacts(p);
        Set<String> all = f.siteNames();
        Map<LocalDate, BigDecimal> settled = settledByDay(p);
        List<ReportFinance> rows = new ArrayList<>();
        for (Bucket b : ReportPeriods.buckets(p, today())) {
            Set<LocalDate> days = Set.copyOf(b.days());
            Agg a = f.agg(x -> days.contains(x.date()), all, b.days().size());
            BigDecimal settle = b.days().stream()
                    .map(d -> settled.getOrDefault(d, BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            rows.add(new ReportFinance(b.label(), a.gmv(), a.cost(), money(settle), a.net(), f.currency()));
        }
        return Pages.of(rows.stream().filter(r -> Kw.hit(keyword, r.period())).toList(), page, size);
    }

    private Map<LocalDate, BigDecimal> settledByDay(Period p) {
        Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = shareFacts.settledByDay(
                ReportPeriods.fmt(ReportPeriods.from(p, today())), ReportPeriods.fmt(ReportPeriods.to(today())));
        for (Map<String, Object> r : rows) {
            out.merge(toDate(r.get("bizDate")), money(r.get("settle")), BigDecimal::add);
        }
        return out;
    }

    // ————————————————————————————————————————————————————————————
    // 趋势 + 汇总条
    // ————————————————————————————————————————————————————————————

    @Override
    public ReportTrend trend(String kind, String period) {
        String k = switch (kind == null ? "" : kind.toUpperCase()) {
            case "DEVICE", "LOCATION", "FINANCE" -> kind.toUpperCase();
            default -> "FINANCE";
        };
        Period p = ReportPeriods.normalize(period);
        Facts f = periodFacts(p);
        Set<String> all = f.siteNames();
        List<ReportTrendPoint> points = new ArrayList<>();
        for (Bucket b : ReportPeriods.buckets(p, today())) {
            Set<LocalDate> days = Set.copyOf(b.days());
            Agg a = f.agg(x -> days.contains(x.date()), all, b.days().size());
            points.add(new ReportTrendPoint(b.label(), a.orders, a.gmv(), a.cost(), a.cost(),
                    a.net(), a.onlineRate(), a.faultRate(), a.turnover()));
        }
        // 汇总条走全周期的同一个折叠器，所以 Σpoints == summary 是结构性的（ReportReadModelTest 断言）。
        Agg total = f.agg(x -> true, all, p.days());
        BigDecimal settle = settledByDay(p).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReportTrend(k, p.name(), points, summary(k, total, money(settle)), f.currency());
    }

    /** 汇总条标签与顺序对齐 ops-web mock 的 {@code summaryOf}，页面不做映射。 */
    private static List<ReportSummaryItem> summary(String kind, Agg a, BigDecimal settle) {
        return switch (kind) {
            case "DEVICE" -> List.of(
                    new ReportSummaryItem("平均在线率", BigDecimal.valueOf(a.onlineRate()), "RATE"),
                    new ReportSummaryItem("周期订单", BigDecimal.valueOf(a.orders), "NUMBER"),
                    new ReportSummaryItem("平均翻台率", a.turnover(), "NUMBER"),
                    new ReportSummaryItem("平均故障率", BigDecimal.valueOf(a.faultRate()), "RATE"));
            case "LOCATION" -> List.of(
                    new ReportSummaryItem("总营收", a.gmv(), "MONEY"),
                    new ReportSummaryItem("总成本", a.cost(), "MONEY"),
                    new ReportSummaryItem("毛利", a.net(), "MONEY"),
                    new ReportSummaryItem("整体 ROI", a.roi(), "RATE"));
            default -> List.of(
                    new ReportSummaryItem("GMV", a.gmv(), "MONEY"),
                    new ReportSummaryItem("分润", a.cost(), "MONEY"),
                    new ReportSummaryItem("应结算", settle, "MONEY"),
                    new ReportSummaryItem("净收入", a.net(), "MONEY"));
        };
    }

    // ————————————————————————————————————————————————————————————
    // 实时大屏
    // ————————————————————————————————————————————————————————————

    @Override
    public PageResult<ReportScreen> screen(Integer page, Integer size, String keyword) {
        List<ReportScreen> kpis = screenBoard().kpis();
        return Pages.of(kpis.stream().filter(x -> Kw.hit(keyword, x.metric())).toList(), page, size);
    }

    /**
     * 工作台聚合（自 {@code OpsController} 的 SeedData 骨架迁入，改为真表实算）。
     *
     * <p>今日口径同大屏（今日 00:00 到现在）；趋势与站点排行取<b>近 7 日含今日</b> ——
     * 排行不用单日是因为凌晨时段单日 GMV 几乎全 0，排行会退化成站点名排序。
     */
    @Override
    public DashboardStats dashboard() {
        LocalDate today = today();
        LocalDate from = today.minusDays(6);
        Facts f = new Facts(from, today);
        Set<String> all = f.siteNames();

        Agg t = f.agg(x -> x.date().equals(today), all, 1);
        List<DashboardTrendDay> trend = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            LocalDate dd = d;
            Agg a = f.agg(x -> x.date().equals(dd), all, 1);
            trend.add(new DashboardTrendDay(ReportPeriods.fmt(dd), a.gmv(), a.orders));
        }

        DashboardTodos todos = new DashboardTodos(woFacts.pendingDispatchCount(),
                refundFacts.pendingCount(), withdrawFacts.pendingCount());

        List<DashboardAlert> alerts = alarmFacts.openAlarms().stream().map(r -> {
            String cab = str(r.get("cabinetNo"));
            String code = str(r.get("alarmCode"));
            return new DashboardAlert(str(r.get("alarmNo")), alertType(code), cab,
                    code + " · " + cab, "/devices/detail?no=" + cab);
        }).toList();

        List<Object[]> raw = new ArrayList<>();
        for (String name : all) {
            Agg a = f.agg(x -> true, Set.of(name), 1);
            if (a.orders == 0) continue;   // 无单站点不进排行，排行是营收榜不是站点名录
            raw.add(new Object[]{name, a.gmv(), a.orders});
        }
        raw.sort(Comparator.<Object[], BigDecimal>comparing(r -> (BigDecimal) r[1]).reversed()
                .thenComparing((Object[] r) -> (String) r[0]));
        List<DashboardRankItem> rankings = new ArrayList<>();
        for (int i = 0; i < Math.min(5, raw.size()); i++) {
            Object[] r = raw.get(i);
            rankings.add(new DashboardRankItem(i + 1, (String) r[0], (BigDecimal) r[1],
                    (Long) r[2], f.currency()));
        }

        long online = t.online;
        long total = t.cabinets;
        return new DashboardStats(t.gmv(), t.orders, (int) online,
                total == 0 ? 0d : online / (double) total,
                woFacts.openCount(), f.currency(), trend, todos, alerts, rankings);
    }

    /** 告警码 → 前端提醒条类型（{@code OFFLINE | EXCEPTION | TIMEOUT}），对不上的一律 EXCEPTION。 */
    private static String alertType(String code) {
        if (code == null) return "EXCEPTION";
        String c = code.toUpperCase();
        if (c.contains("OFFLINE")) return "OFFLINE";
        if (c.contains("TIMEOUT")) return "TIMEOUT";
        return "EXCEPTION";
    }

    /**
     * 大屏：<b>今日 00:00 到当前小时</b>（与周期报表的「到昨日」刻意不同，见接口注释）。
     *
     * <p>KPI 的今日 GMV/订单**从分时序列反算**，不另查一遍 —— 「大屏 KPI 与它下面的曲线对不上」
     * 是最刺眼的假。环比与**昨日同一时段**比（跟昨日整日比会永远显示掉量）。
     */
    @Override
    public ScreenBoard screenBoard() {
        LocalDate today = today();
        LocalDate yesterday = today.minusDays(1);
        int nowHour = clock.instant().atZone(ZoneOffset.UTC).getHour();
        Facts f = new Facts(yesterday, today);
        Set<String> all = f.siteNames();

        List<ScreenBoardPoint> hourly = new ArrayList<>();
        for (int h = 0; h <= nowHour; h++) {
            int hh = h;
            Agg a = f.agg(x -> x.date().equals(today) && x.hour() == hh, all, 1);
            hourly.add(new ScreenBoardPoint(String.format("%02d:00", h), a.gmv(), a.orders));
        }
        BigDecimal gmvToday = hourly.stream().map(ScreenBoardPoint::gmv).reduce(BigDecimal.ZERO, BigDecimal::add);
        long ordersToday = hourly.stream().mapToLong(ScreenBoardPoint::orders).sum();

        Agg yest = f.agg(x -> x.date().equals(yesterday) && x.hour() <= nowHour, all, 1);
        Agg fleet = f.agg(x -> false, all, 1); // 只要机柜快照，不要订单事实
        long online = fleet.online;
        long faults = fleet.faults;
        long total = fleet.cabinets;

        List<ReportScreen> kpis = List.of(
                new ReportScreen("今日GMV", money(gmvToday), f.currency(), delta(gmvToday, yest.gmv())),
                new ReportScreen("今日订单", BigDecimal.valueOf(ordersToday), "单",
                        delta(BigDecimal.valueOf(ordersToday), BigDecimal.valueOf(yest.orders))),
                new ReportScreen("在线柜机", BigDecimal.valueOf(online), "台", 0d),
                new ReportScreen("在线率", pct(online, total), "%", 0d),
                new ReportScreen("借出中充电宝", BigDecimal.valueOf(orderFacts.inUseCount()), "个", 0d),
                new ReportScreen("待处理工单", BigDecimal.valueOf(woFacts.openCount()), "单", 0d),
                // 活跃用户是去重计数，逐小时不可相加，故单独一条 SQL；环比留 0 ——
                // 该 SQL 只能按整日去重，拿「今日到现在」比「昨日整日」必然一直显示掉量。
                new ReportScreen("活跃用户", BigDecimal.valueOf(
                        orderFacts.activeUsers(ReportPeriods.fmt(today), ReportPeriods.fmt(today))), "人", 0d),
                new ReportScreen("翻台率", ratio(ordersToday, online), "次/柜", 0d));

        List<ScreenRankRow> ranking = new ArrayList<>();
        List<Object[]> raw = new ArrayList<>();
        for (String name : all) {
            Agg a = f.agg(x -> x.date().equals(today) && x.hour() <= nowHour, Set.of(name), 1);
            raw.add(new Object[]{f.snap(name).siteNo(), name, a.gmv(), a.orders});
        }
        raw.sort(Comparator.<Object[], BigDecimal>comparing(r -> (BigDecimal) r[2]).reversed()
                .thenComparing((Object[] r) -> (String) r[0]));
        for (int i = 0; i < raw.size(); i++) {
            Object[] r = raw.get(i);
            ranking.add(new ScreenRankRow(i + 1, (String) r[0], (String) r[1],
                    (BigDecimal) r[2], (Long) r[3]));
        }

        // 三片互斥且穷尽 → Σ = 机柜总数（快照 SQL 里 onlineCount 已排除 FAULT，见 mapper 注释）。
        List<ScreenStatusSlice> status = List.of(
                new ScreenStatusSlice("在线", online),
                new ScreenStatusSlice("故障", faults),
                new ScreenStatusSlice("离线", total - online - faults));

        String updatedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
        return new ScreenBoard(updatedAt, f.currency(), kpis, hourly, ranking, status);
    }

    // ————————————————————————————————————————————————————————————
    // 自定义报表
    // ————————————————————————————————————————————————————————————

    @Override
    public List<ReportMetricDef> metrics() {
        return METRICS;
    }

    @Override
    public PageResult<ReportCustom> custom(Integer page, Integer size, String keyword,
                                           String period, String dim, String metrics) {
        Period p = ReportPeriods.normalize(period);
        Facts f = periodFacts(p);
        List<String> keys = metricKeys(metrics);
        List<ReportCustom> rows = new ArrayList<>();
        for (Map.Entry<String, Group> e : groups(f, p, dim).entrySet()) {
            Group g = e.getValue();
            Agg a = f.agg(x -> g.days == null || g.days.contains(x.date()), g.sites, g.days == null
                    ? p.days() : g.days.size());
            for (String k : keys) {
                rows.add(new ReportCustom(e.getKey(), k, metricValue(k, a)));
            }
        }
        return Pages.of(rows.stream().filter(r -> Kw.hit(keyword, r.dim(), r.metric())).toList(), page, size);
    }

    /** 一个自定义报表分组：站点集合 + 日期集合（{@code days == null} = 整个周期）。 */
    private record Group(Set<String> sites, Set<LocalDate> days) {
    }

    /** 维度分组：站点名 / 场景类型 / 周期桶。三者都落在既有主数据或周期桶上，不另造维度。 */
    private Map<String, Group> groups(Facts f, Period p, String dim) {
        Map<String, Group> out = new LinkedHashMap<>();
        String d = dim == null ? "" : dim.toUpperCase();
        if ("SCENE".equals(d)) {
            for (String name : sorted(f)) {
                out.computeIfAbsent(f.snap(name).sceneType(), x -> new Group(new LinkedHashSet<>(), null))
                        .sites().add(name);
            }
            return out;
        }
        if ("MONTH".equals(d)) {
            // 与 mock 一致：MONTH 维度就是「周期桶」维度（LAST_30D 下即逐日），
            // 不额外按自然月切 —— 否则同一页里桶标签会有两套。
            Set<String> all = f.siteNames();
            for (Bucket b : ReportPeriods.buckets(p, today())) {
                out.put(b.label(), new Group(all, Set.copyOf(b.days())));
            }
            return out;
        }
        for (String name : sorted(f)) {
            out.put(name, new Group(Set.of(name), null));
        }
        return out;
    }

    private static List<String> metricKeys(String csv) {
        List<String> requested = csv == null || csv.isBlank() ? METRICS_DEFAULT
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        // 目录外的 key 静默丢弃：前端勾选框来自同一份目录，出现未知 key 只可能是手拼 URL。
        return requested.stream().filter(k -> METRICS.stream().anyMatch(m -> m.key().equals(k))).toList();
    }

    private static BigDecimal metricValue(String key, Agg a) {
        return switch (key) {
            case "GMV" -> a.gmv();
            case "ORDERS" -> BigDecimal.valueOf(a.orders);
            case "AOV" -> a.aov();
            case "COST" -> a.cost();
            case "NET" -> a.net();
            case "ONLINE_RATE" -> BigDecimal.valueOf(a.onlineRate());
            case "TURNOVER" -> a.turnover();
            case "FAULT_RATE" -> BigDecimal.valueOf(a.faultRate());
            default -> BigDecimal.ZERO;
        };
    }

    // ————————————————————————————————————————————————————————————
    // 消费者洞察
    // ————————————————————————————————————————————————————————————

    /** 一个成功借出过的用户的存量画像。 */
    private record BorrowUser(String userNo, long orders, BigDecimal gmv, String currency,
                              LocalDate lastDate, int firstHour) {
    }

    private List<BorrowUser> borrowUsers() {
        List<BorrowUser> out = new ArrayList<>();
        for (Map<String, Object> r : orderFacts.borrowUsers()) {
            out.add(new BorrowUser(str(r.get("cUserNo")), l(r.get("orders")), money(r.get("gmv")),
                    blankTo(str(r.get("currency")), FALLBACK_CURRENCY),
                    toDate(r.get("lastDate")), i(r.get("firstHour"))));
        }
        return out;
    }

    /**
     * 人群分层：按**最近借出距今天数**分三段。
     *
     * <p>不按累计单量分层是刻意的：那样 {@code repeatRate}（段内复借用户占比）会退化成
     * 0 / 1 / 1，表面上是个比率、实际是分层定义的同义反复，一眼假。
     */
    @Override
    public PageResult<ConsumerSegment> consumerSegments(Integer page, Integer size, String keyword) {
        List<BorrowUser> users = borrowUsers();
        LocalDate today = today();
        List<ConsumerSegment> rows = List.of(
                segment("SEG1", "活跃用户（7 日内借出）", users, today, 0, 7),
                segment("SEG2", "沉默用户（8~30 日）", users, today, 8, 30),
                segment("SEG3", "流失用户（30 日以上）", users, today, 31, Integer.MAX_VALUE));
        return Pages.of(rows.stream().filter(r -> Kw.hit(keyword, r.segmentNo(), r.segment())).toList(),
                page, size);
    }

    private ConsumerSegment segment(String no, String label, List<BorrowUser> users,
                                    LocalDate today, int idleFrom, int idleTo) {
        List<BorrowUser> in = users.stream().filter(u -> {
            long idle = ChronoUnit.DAYS.between(u.lastDate(), today);
            return idle >= idleFrom && idle <= idleTo;
        }).toList();
        long orders = in.stream().mapToLong(BorrowUser::orders).sum();
        BigDecimal gmv = in.stream().map(BorrowUser::gmv).reduce(BigDecimal.ZERO, BigDecimal::add);
        long repeat = in.stream().filter(u -> u.orders() >= 2).count();
        return new ConsumerSegment(no, label, (long) in.size(),
                in.isEmpty() ? 0d : rate(repeat / (double) in.size()),
                orders == 0 ? BigDecimal.ZERO : gmv.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP),
                in.stream().map(BorrowUser::currency).findFirst().orElse(FALLBACK_CURRENCY));
    }

    /**
     * 漏斗 + 画像。
     *
     * <p><b>只有三个环节</b>：「扫码进入」「授权登录」没有埋点表，反推它们需要一组假定转化率
     * ——那是编数字，宁可让漏斗短。{@code totalUsers} = 「成功借出」人数 = 分层表用户数合计
     * （同一 tab 的表与图必须是同一批人）。
     *
     * <p><b>画像只有「借出时段」一个维度</b>：年龄段无列、终端只有登录方式且覆盖不全，
     * 硬凑会破坏「同维 Σvalue = totalUsers」这条前端拿来画饼图的不变量。
     */
    @Override
    public ConsumerInsight consumerInsight() {
        List<BorrowUser> users = borrowUsers();
        long borrowed = users.size();
        long ordering = orderFacts.orderingUsers();
        long repeat = users.stream().filter(u -> u.orders() >= 2).count();

        long[] stages = {ordering, borrowed, repeat};
        List<String> labels = List.of("创建订单", "成功借出", "复借");
        List<ConsumerFunnelStage> funnel = new ArrayList<>();
        long head = Math.max(1, stages[0]);
        for (int i = 0; i < stages.length; i++) {
            long cur = stages[i];
            long prev = i == 0 ? cur : stages[i - 1];
            funnel.add(new ConsumerFunnelStage(labels.get(i), cur, rate(cur / (double) head),
                    i == 0 || prev == 0 ? 0d : rate(1 - cur / (double) prev)));
        }

        List<ConsumerProfileSlice> profiles = new ArrayList<>();
        Map<String, Long> byPeriod = new LinkedHashMap<>();
        for (String label : List.of("早高峰", "日间", "晚间", "夜间")) {
            byPeriod.put(label, 0L);
        }
        for (BorrowUser u : users) {
            byPeriod.merge(hourBucket(u.firstHour()), 1L, Long::sum);
        }
        for (Map.Entry<String, Long> e : byPeriod.entrySet()) {
            profiles.add(new ConsumerProfileSlice("PERIOD", "借出时段", e.getKey(), e.getValue(),
                    borrowed == 0 ? 0d : rate(e.getValue() / (double) borrowed)));
        }
        return new ConsumerInsight(funnel, profiles, borrowed);
    }

    /** 首次借出时段分桶。四段互斥且覆盖 0~23，故 Σ 必然等于 totalUsers。 */
    private static String hourBucket(int hour) {
        if (hour >= 6 && hour <= 9) return "早高峰";
        if (hour >= 10 && hour <= 16) return "日间";
        if (hour >= 17 && hour <= 21) return "晚间";
        return "夜间";
    }

    // ————————————————————————————————————————————————————————————
    // 给 loc 域坪效端点复用
    // ————————————————————————————————————————————————————————————

    @Override
    public List<SiteRollup> siteRollups(LocalDate from, LocalDate to) {
        int days = (int) Math.max(1, ChronoUnit.DAYS.between(from, to) + 1);
        Facts f = new Facts(from, to);
        List<SiteRollup> out = new ArrayList<>();
        for (String name : sorted(f)) {
            Agg a = f.agg(x -> true, Set.of(name), days);
            Snap s = f.snap(name);
            BigDecimal shareRate = a.gmv().signum() == 0 ? BigDecimal.ZERO
                    : a.cost().divide(a.gmv(), 4, RoundingMode.HALF_UP);
            out.add(new SiteRollup(name, s.siteNo(), s.sceneType(), a.orders, a.gmv(), a.cost(),
                    a.cabinets, a.online, a.faults, days, shareRate, f.currency()));
        }
        return out;
    }

    // ————————————————————————————————————————————————————————————
    // 工具
    // ————————————————————————————————————————————————————————————

    /** 报表行顺序：先按 siteNo 再按名字，保证分页稳定（无序的分页会让第 2 页出现第 1 页的行）。 */
    private static List<String> sorted(Facts f) {
        return f.siteNames().stream()
                .sorted(Comparator.comparing((String n) -> f.snap(n).siteNo()).thenComparing(n -> n))
                .toList();
    }

    private static double delta(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() <= 0) {
            return 0d;
        }
        return rate(now.divide(before, 6, RoundingMode.HALF_UP).doubleValue() - 1);
    }

    private static BigDecimal pct(long part, long whole) {
        return whole == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(long part, long whole) {
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(Math.max(1, whole)), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(Object v) {
        return money(v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v == null ? "0" : v)));
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate4(Object v) {
        return v == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(v)).setScale(4, RoundingMode.HALF_UP);
    }

    private static double rate(double v) {
        return Math.round(v * 10000d) / 10000d;
    }

    private static long l(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static int i(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    /** {@code DATE()} 在 MariaDB 驱动下可能回 {@code java.sql.Date} 或 {@code LocalDate}，两种都收。 */
    private static LocalDate toDate(Object v) {
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }
}
