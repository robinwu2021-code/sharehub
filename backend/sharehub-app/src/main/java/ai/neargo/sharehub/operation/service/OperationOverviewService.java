package ai.neargo.sharehub.operation.service;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.finance.entity.ShareRule;
import ai.neargo.sharehub.finance.mapper.ShareRuleMapper;
import ai.neargo.sharehub.loc.entity.LocContract;
import ai.neargo.sharehub.loc.mapper.LocMappers.ContractMapper;
import ai.neargo.sharehub.operation.dto.OperationDtos.*;
import ai.neargo.sharehub.operation.mapper.OperationMappers.CabinetStatMapper;
import ai.neargo.sharehub.operation.mapper.OperationMappers.OrderStatMapper;
import ai.neargo.sharehub.operation.mapper.OperationMappers.SiteScaleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 站点概览（OM-S1）与单站统计（OM-S2）。
 *
 * <p><b>口径与前端 mock 逐条对齐</b>（`lib/rules/operation-overview.ts`）：同一页在 mock 与真实
 * 后端下算出两个不同的数，比没有这一页更糟 —— 运营会以为是数据出了问题。
 *
 * <p><b>为什么待关注的判定在 Java 里做而不是写成一条 SQL</b>：七条规则里有四条要跨三张表
 * 并带「多久没心跳」「还有几天到期」这类时间算术，拼成一条 SQL 之后没人能改。
 * 规模控制在「站点数」这个量级（百级），逐站判定的成本可以忽略；真正会长到十万行的是订单，
 * 而订单侧全部走了聚合 SQL。
 */
@Service
public class OperationOverviewService {

    /** 默认统计窗口：近 7 天（与页面默认筛选一致）。 */
    private static final int DEFAULT_DAYS = 7;

    /** 合同「即将到期」的提前量。30 天是续签谈判的常见起点。 */
    private static final int CONTRACT_SOON_DAYS = 30;

    /** 全部离线多久才值得报：2 小时。短暂抖动天天有，报出来就没人看了。 */
    private static final int OFFLINE_ALERT_HOURS = 2;

    private static final String CURRENCY = "AED";

    private final SiteScaleMapper sites;
    private final CabinetStatMapper cabinets;
    private final OrderStatMapper orders;
    private final ContractMapper contracts;
    private final ShareRuleMapper shareRules;
    private final ai.neargo.sharehub.loc.mapper.LocMappers.LocationMapper locations;

    public OperationOverviewService(SiteScaleMapper sites, CabinetStatMapper cabinets,
                                    OrderStatMapper orders, ContractMapper contracts,
                                    ShareRuleMapper shareRules,
                                    ai.neargo.sharehub.loc.mapper.LocMappers.LocationMapper locations) {
        this.locations = locations;
        this.sites = sites;
        this.cabinets = cabinets;
        this.orders = orders;
        this.contracts = contracts;
        this.shareRules = shareRules;
    }

    // ——————————————————————— 概览 ———————————————————————

    public OperationOverview overview(String from, String to) {
        String toDate = dateOf(to, LocalDate.now());
        String fromDate = dateOf(from, LocalDate.parse(toDate).minusDays(DEFAULT_DAYS - 1L));
        long days = Math.max(1, ChronoUnit.DAYS.between(LocalDate.parse(fromDate), LocalDate.parse(toDate)) + 1);

        Map<String, Object> siteScale = sites.scale();
        Map<String, Object> cabScale = cabinets.scale();
        int cabTotal = i(cabScale.get("total"));
        int cabOnline = i(cabScale.get("online"));

        Map<String, Object> ordSummary = orders.summary(fromDate, toDate);
        int orderCount = i(ordSummary.get("orders"));
        BigDecimal gmv = money(ordSummary.get("gmv"));

        OverviewScale scale = new OverviewScale(
                i(siteScale.get("total")), i(siteScale.get("active")), i(siteScale.get("paused")),
                pointTotal(), cabTotal, cabOnline, rate(cabOnline, cabTotal),
                // 充电宝四项暂由机柜仓位推不出来，交给设备域的真实统计——这里**如实给 0**
                // 而不是编一个近似值：概览上的假数字会被当真数字用来做决策
                0, 0, 0, 0,
                i(siteScale.get("preparing")), i(siteScale.get("withdrawing")), i(siteScale.get("closed")));

        OverviewBusiness business = new OverviewBusiness(orderCount, gmv, CURRENCY,
                orderCount == 0 ? BigDecimal.ZERO : gmv.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP),
                cabTotal == 0 ? 0 : round2((double) orderCount / cabTotal / days));

        Map<String, int[]> cabBySite = new HashMap<>();
        Map<String, String> lastBeatBySite = new HashMap<>();
        for (Map<String, Object> r : cabinets.bySite()) {
            String no = s(r.get("siteNo"));
            cabBySite.put(no, new int[]{i(r.get("total")), i(r.get("online"))});
            lastBeatBySite.put(no, s(r.get("lastHeartbeatAt")));
        }
        Map<String, Map<String, Object>> ordBySite = new HashMap<>();
        for (Map<String, Object> r : orders.bySite(fromDate, toDate)) ordBySite.put(s(r.get("siteNo")), r);

        List<Map<String, Object>> siteRows = sites.sites();
        List<SiteRankRow> ranking = new ArrayList<>();
        for (Map<String, Object> st : siteRows) {
            String no = s(st.get("siteNo"));
            int[] cab = cabBySite.getOrDefault(no, new int[]{0, 0});
            Map<String, Object> o = ordBySite.get(no);
            int oc = o == null ? 0 : i(o.get("orders"));
            ranking.add(new SiteRankRow(no, s(st.get("name")), s(st.get("venueName")), cab[0],
                    o == null ? BigDecimal.ZERO : money(o.get("gmv")), oc,
                    cab[0] == 0 ? 0 : round2((double) oc / cab[0] / days),
                    rate(cab[1], cab[0])));
        }
        ranking.sort(Comparator.comparing(SiteRankRow::gmv).reversed());

        return new OperationOverview(scale, business,
                trendPoints(orders.trend(fromDate, toDate), fromDate, toDate),
                ranking,
                attention(siteRows, cabBySite, lastBeatBySite, toDate),
                scenes(ordBySite, siteRows),
                new GeoReady(i(siteScale.get("withGeo")), i(siteScale.get("total"))));
    }

    /**
     * 待关注站点。规则与 `attentionOf` 逐条对齐，顺序按严重度。
     *
     * <p><b>没有命中任何规则的站点不出现在这里</b> —— 这一页是「要处理什么」，
     * 把正常站点也列出来，它就退化成了第二张站点列表。
     */
    private List<AttentionItem> attention(List<Map<String, Object>> siteRows,
                                          Map<String, int[]> cabBySite,
                                          Map<String, String> lastBeatBySite,
                                          String toDate) {
        // 分成配置：有 VENUE/AGENT 规则或有生效合同，都算「配了」
        Set<String> sharedSites = new HashSet<>();
        for (LocContract c : contracts.selectList(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getStatus, "ACTIVE"))) {
            if (c.getSiteNo() != null) sharedSites.add(c.getSiteNo());
        }
        boolean anyRule = shareRules.selectCount(new LambdaQueryWrapper<ShareRule>()) > 0;

        Map<String, List<LocContract>> contractsBySite = new HashMap<>();
        for (LocContract c : contracts.selectList(new LambdaQueryWrapper<>())) {
            if (c.getSiteNo() != null) contractsBySite.computeIfAbsent(c.getSiteNo(), k -> new ArrayList<>()).add(c);
        }
        Set<String> withOrders = new HashSet<>(orders.siteNosWithOrdersSince(
                LocalDate.parse(toDate).minusDays(6).toString()));

        List<AttentionItem> out = new ArrayList<>();
        LocalDate today = LocalDate.parse(toDate);
        for (Map<String, Object> st : siteRows) {
            String no = s(st.get("siteNo"));
            String name = s(st.get("name"));
            boolean active = "ACTIVE".equals(s(st.get("status")));
            int[] cab = cabBySite.getOrDefault(no, new int[]{0, 0});

            if (cab[0] > 0 && cab[1] == 0) {
                Integer hours = offlineHours(lastBeatBySite.get(no));
                if (hours == null || hours >= OFFLINE_ALERT_HOURS) {
                    out.add(new AttentionItem(no, name, "ALL_OFFLINE", "high",
                            cab[0] + " 台机柜全部离线" + (hours == null ? "" : " " + hours + " 小时")));
                }
            }
            if (active && cab[0] == 0) {
                out.add(new AttentionItem(no, name, "NO_CABINET", "medium", "还没有机柜"));
            }
            List<LocContract> cs = contractsBySite.getOrDefault(no, List.of());
            LocContract expired = cs.stream().filter(c -> daysTo(c.getEndAt(), today) < 0).findFirst().orElse(null);
            LocContract soon = cs.stream()
                    .filter(c -> { long d = daysTo(c.getEndAt(), today); return d >= 0 && d <= CONTRACT_SOON_DAYS; })
                    .findFirst().orElse(null);
            if (expired != null && active) {
                out.add(new AttentionItem(no, name, "CONTRACT_EXPIRED", "high",
                        "合同 " + expired.getContractNo() + " 已于 " + dateHead(expired.getEndAt()) + " 到期，站点仍在营业"));
            } else if (soon != null) {
                out.add(new AttentionItem(no, name, "CONTRACT_SOON", "medium",
                        "合同 " + soon.getContractNo() + " 将在 " + daysTo(soon.getEndAt(), today) + " 天后到期"));
            }
            if (!sharedSites.contains(no) && !anyRule) {
                out.add(new AttentionItem(no, name, "NO_SHARING", "medium", "没有配置分成方，收入全部留在平台"));
            }
            if (active && cab[0] > 0 && !withOrders.contains(no)) {
                out.add(new AttentionItem(no, name, "NO_ORDER", "low", "近 7 日没有一单"));
            }
        }
        Map<String, Integer> sev = Map.of("high", 0, "medium", 1, "low", 2);
        out.sort(Comparator.comparingInt(a -> sev.getOrDefault(a.severity(), 3)));
        return out;
    }

    private List<SceneShare> scenes(Map<String, Map<String, Object>> ordBySite,
                                    List<Map<String, Object>> siteRows) {
        Map<String, String> sceneOf = new HashMap<>();
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Map<String, Object> st : siteRows) {
            String scene = s(st.get("sceneType")) == null ? "未分类" : s(st.get("sceneType"));
            sceneOf.put(s(st.get("siteNo")), scene);
            count.merge(scene, 1, Integer::sum);
        }
        Map<String, BigDecimal> gmv = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : ordBySite.entrySet()) {
            String scene = sceneOf.get(e.getKey());
            if (scene != null) gmv.merge(scene, money(e.getValue().get("gmv")), BigDecimal::add);
        }
        return count.entrySet().stream()
                .map(e -> new SceneShare(e.getKey(), e.getValue(), gmv.getOrDefault(e.getKey(), BigDecimal.ZERO)))
                .sorted(Comparator.comparing(SceneShare::siteCount).reversed())
                .toList();
    }

    // ——————————————————————— 单站统计 ———————————————————————

    public SiteStats siteStats(String siteNo, String from, String to) {
        Map<String, Object> site = sites.sites().stream()
                .filter(r -> siteNo.equals(s(r.get("siteNo")))).findFirst()
                .orElseThrow(() -> BizException.notFound(siteNo));

        String toDate = dateOf(to, LocalDate.now());
        String fromDate = dateOf(from, LocalDate.parse(toDate).minusDays(DEFAULT_DAYS - 1L));
        long days = Math.max(1, ChronoUnit.DAYS.between(LocalDate.parse(fromDate), LocalDate.parse(toDate)) + 1);

        int[] cab = cabinets.bySite().stream()
                .filter(r -> siteNo.equals(s(r.get("siteNo"))))
                .findFirst()
                .map(r -> new int[]{i(r.get("total")), i(r.get("online"))})
                .orElse(new int[]{0, 0});

        Map<String, Object> sum = orders.summaryOfSite(siteNo, fromDate, toDate);
        int oc = i(sum.get("orders"));
        BigDecimal gmv = money(sum.get("gmv"));

        Map<String, Integer> cabByPoint = new HashMap<>();
        Map<String, String> nameByPoint = new HashMap<>();
        for (Map<String, Object> r : cabinets.byPoint(siteNo)) {
            cabByPoint.put(s(r.get("locationNo")), i(r.get("cabinetCount")));
            nameByPoint.put(s(r.get("locationNo")), s(r.get("locationName")));
        }
        Map<String, Map<String, Object>> ordByPoint = new HashMap<>();
        for (Map<String, Object> r : orders.byPointOfSite(siteNo, fromDate, toDate)) {
            ordByPoint.put(s(r.get("locationNo")), r);
        }
        List<PointStat> byPoint = cabByPoint.keySet().stream().sorted().map(no -> {
            Map<String, Object> o = ordByPoint.get(no);
            int n = o == null ? 0 : i(o.get("orders"));
            int c = cabByPoint.getOrDefault(no, 0);
            return new PointStat(no, nameByPoint.get(no), c, n,
                    o == null ? BigDecimal.ZERO : money(o.get("gmv")),
                    c == 0 ? 0 : round2((double) n / c / days));
        }).toList();

        return new SiteStats(siteNo, s(site.get("name")), fromDate, toDate, oc, gmv, CURRENCY,
                oc == 0 ? BigDecimal.ZERO : gmv.divide(BigDecimal.valueOf(oc), 2, RoundingMode.HALF_UP),
                i(sum.get("avgDurationMin")),
                cab[0] == 0 ? 0 : round2((double) oc / cab[0] / days),
                rate(cab[1], cab[0]), cab[0],
                trendPoints(orders.trendOfSite(siteNo, fromDate, toDate), fromDate, toDate), byPoint);
    }

    // ——————————————————————— 工具 ———————————————————————

    /**
     * 补齐区间内**没有订单的那些天**。
     *
     * <p>不补的话折线图会把 7 月 3 日和 7 月 9 日画成相邻两点，看上去像连续经营 ——
     * 而真相是中间六天一单没有。
     */
    private static List<TrendPoint> trendPoints(List<Map<String, Object>> rows, String fromDate, String toDate) {
        Map<String, Map<String, Object>> byDay = new HashMap<>();
        for (Map<String, Object> r : rows) byDay.put(s(r.get("day")), r);
        List<TrendPoint> out = new ArrayList<>();
        for (LocalDate d = LocalDate.parse(fromDate); !d.isAfter(LocalDate.parse(toDate)); d = d.plusDays(1)) {
            Map<String, Object> r = byDay.get(d.toString());
            out.add(new TrendPoint(d.toString(), r == null ? 0 : i(r.get("orders")),
                    r == null ? BigDecimal.ZERO : money(r.get("gmv"))));
        }
        return out;
    }

    /** 在用点位总数。归档的不算 —— 概览的规模指标说的是「现在在经营的有多少」。 */
    private int pointTotal() {
        Long n = locations.selectCount(new LambdaQueryWrapper<ai.neargo.sharehub.loc.entity.LocLocation>()
                .isNull(ai.neargo.sharehub.loc.entity.LocLocation::getArchivedAt));
        return n == null ? 0 : Math.toIntExact(n);
    }

    private static String dateOf(String iso, LocalDate fallback) {
        if (iso == null || iso.isBlank()) return fallback.toString();
        return iso.length() >= 10 ? iso.substring(0, 10) : fallback.toString();
    }

    private static String dateHead(String iso) {
        return iso == null ? "" : (iso.length() >= 10 ? iso.substring(0, 10) : iso);
    }

    private static long daysTo(String endAt, LocalDate today) {
        String d = dateHead(endAt);
        if (d.isEmpty()) return Long.MAX_VALUE;
        try {
            return ChronoUnit.DAYS.between(today, LocalDate.parse(d));
        } catch (RuntimeException e) {
            return Long.MAX_VALUE;   // 脏日期不该让整页 500
        }
    }

    private static Integer offlineHours(String lastBeat) {
        if (lastBeat == null || lastBeat.length() < 10) return null;
        try {
            java.time.Instant t = java.time.Instant.parse(
                    lastBeat.endsWith("Z") ? lastBeat : lastBeat.replace(" ", "T") + "Z");
            return (int) ChronoUnit.HOURS.between(t, java.time.Instant.now());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double rate(int part, int total) {
        return total == 0 ? 0 : round2((double) part / total);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static int i(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static BigDecimal money(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private static String s(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
