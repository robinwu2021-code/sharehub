package ai.neargo.sharehub.seed;

import ai.neargo.sharehub.agent.dto.AgentDtos.Agent;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.loc.dto.LocDtos.Location;
import ai.neargo.sharehub.loc.dto.LocDtos.Venue;
import ai.neargo.sharehub.loc.dto.LocDtos.Contract;
import ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder;
import ai.neargo.sharehub.dto.Dto.*;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Slot;
import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;
import ai.neargo.sharehub.agent.dto.AgentDtos.Agent;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.loc.dto.LocDtos.Location;
import ai.neargo.sharehub.loc.dto.LocDtos.Venue;
import ai.neargo.sharehub.loc.dto.LocDtos.Contract;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 骨架内存种子：镜像 ops-web {@code lib/mock/db.ts} 的数据量与生成逻辑，
 * 使真实后端返回与 mock 一致的形状/规模，页面无缝点亮。
 *
 * <p>无持久化：可变列表仅供 save/blacklist/dispatch/audit 等动作就地演示。
 */
@Component
public class SeedData {

    /**
     * 所有演示数据的时间基准。
     *
     * <p><b>取「当前时刻」而不是写死一个日期</b>：此前这里固定为 2026-07-11，于是不论哪天灌种子，
     * 订单、告警、工单全都落在那一天附近 —— 演示环境一打开，经营看板与站点概览的
     * 近 7 日 / 近 30 日**全是 0**，趋势是一条平线、排行全零。
     * 数据明明有，页面却像坏了，而真相只是「这批数据太老」。
     *
     * <p>按小时取整：一次灌库过程中 BASE 保持不变，同一批数据的相对关系是确定的
     * （合同还有多少天到期、订单隔几小时一单），只是整体跟着灌库那天走。
     */
    private static final Instant BASE = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS);

    private static final List<String> VENDORS = List.of("cd-tech", "sd-power", "chargenow");
    private static final List<String> LOCS = List.of("Dubai Mall L1", "Mall of Emirates", "DXB T3",
            "Marina Walk", "City Centre Deira", "Yas Mall", "Ibn Battuta");
    private static final List<String> VENUE_NAMES = List.of("Emaar Malls", "Majid Al Futtaim",
            "DXB Airports", "Aldar", "Nakheel");
    private static final List<String> REGIONS = List.of("Dubai North", "Dubai Marina", "Deira", "DXB", "JBR");

    private final List<Cabinet> cabinets = new ArrayList<>();
    private final List<RentOrder> orders = new ArrayList<>();
    private final List<WorkOrder> workOrders = new ArrayList<>();
    private final List<Site> sites = new ArrayList<>();
    private final List<Location> locations = new ArrayList<>();
    private final List<Venue> venues = new ArrayList<>();
    private final List<Contract> contracts = new ArrayList<>();
    private final List<ShareRule> shareRules = new ArrayList<>();
    private final List<Settlement> settlements = new ArrayList<>();
    private final List<Withdrawal> withdrawals = new ArrayList<>();
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private final List<Vendor> vendors = new ArrayList<>();
    private final List<CUser> cUsers = new ArrayList<>();
    private final List<Coupon> coupons = new ArrayList<>();
    private final List<PricePlan> pricePlans = new ArrayList<>();
    private final List<Agent> agents = new ArrayList<>();
    private final List<Employee> employees = new ArrayList<>();
    private final List<RoleRow> roles = new ArrayList<>();
    private final List<AuditEntry> audits = new ArrayList<>();
    private DashboardStats dashboard;

    // —— 生成 helper ——
    private static <T> T p(List<T> a, int i) {
        return a.get(Math.floorMod(i, a.size()));
    }

    private static String iso(long offsetMs) {
        return BASE.minusMillis(offsetMs).toString();
    }

    private static String first7(int n) {
        String s = String.valueOf(n);
        return s.length() > 7 ? s.substring(0, 7) : s;
    }

    @PostConstruct
    void init() {
        // —— 设备（48）——
        List<Integer> totals = List.of(6, 8, 12);
        for (int i = 0; i < 48; i++) {
            int total = p(totals, i);
            boolean online = i % 9 != 0;
            cabinets.add(new Cabinet("CAB" + (1000 + i), "SN" + (90000 + i), p(VENDORS, i),
                    p(List.of("X6", "S8", "M12"), i), "LOC" + (200 + i % LOCS.size()), p(LOCS, i),
                    total, (i * 7) % (total + 1), online ? "ONLINE" : "OFFLINE",
                    i % 13 == 0 ? "FAULT" : "DEPLOYED", p(List.of("1.2.0", "1.3.1", "1.4.0"), i),
                    online ? iso(i * 60000L) : null));
        }

        // —— 订单（120）——
        List<String> ostatus = List.of("IN_USE", "SETTLED", "CLOSED", "RETURNED", "EXCEPTION", "CREATED");
        for (int i = 0; i < 120; i++) {
            String st = p(ostatus, i);
            Integer dur = (st.equals("IN_USE") || st.equals("CREATED")) ? null : 20 + (i * 17) % 300;
            boolean returned = st.equals("SETTLED") || st.equals("CLOSED");
            double fee = dur != null ? Math.min(30, Math.ceil(dur / 30.0) * 3) : 0;
            orders.add(new RentOrder("ORD" + (500000 + i), "U" + (3000 + i % 40), p(cabinets, i).cabinetNo(),
                    returned ? p(cabinets, i + 3).cabinetNo() : null, "PB" + (1000 + i), p(LOCS, i), st,
                    iso(i * 3600_000L), dur != null ? iso(i * 3600_000L - dur * 60000L) : null, dur,
                    fee, 50, "AED"));
        }

        // —— 工单（64）——
        List<String> wtype = List.of("FAULT", "REFILL", "INSPECT", "COMPLAINT", "CLEAN");
        List<String> wstatus = List.of("CREATED", "DISPATCHED", "PROCESSING", "DONE", "CLOSED");
        for (int i = 0; i < 64; i++) {
            workOrders.add(new WorkOrder("WO" + (70000 + i), p(wtype, i),
                    p(List.of("ALERT", "USER", "VENUE", "MANUAL"), i), p(List.of("LOW", "MEDIUM", "HIGH"), i),
                    p(cabinets, i).cabinetNo(), p(LOCS, i), p(wstatus, i),
                    i % 3 == 0 ? null : p(List.of("Ali", "Omar", "Sara", "Wang"), i),
                    iso(-(i % 5) * 3600_000L),
                    p(List.of("柜机离线", "缺货补货", "定期巡检", "用户投诉未弹出", "清洁维护"), i),
                    iso(i * 5400_000L)));
        }

        // —— 场所：站点（12）/ 点位（30）/ 场地方（5）/ 合同（18）——
        for (int i = 0; i < 12; i++) {
            /*
             * venueNo 与 VENUE_NAMES 同一个下标取 —— 两处都用 p(...) 循环取模，
             * 编号与名字才对得上。分成链路按编号走，对不上就会把钱分给另一家场地方。
             *
             * 经纬度给**真实的迪拜坐标**（25.05~25.28N / 55.12~55.40E 网格）：
             * 留空的话 C 端「找附近的柜」算不出距离，整个找柜入口在演示里是废的 ——
             * 而这正是 MVP 里标着「线上无数据」的那一条。
             */
            sites.add(new Site("ST" + (300 + i), p(LOCS, i),
                    "VEN" + (300 + i % VENUE_NAMES.size()), p(VENUE_NAMES, i),
                    i % 3 == 0 ? null : "AG" + String.format("%03d", (i % 9) + 1),
                    "BR-DEFAULT",   // 一站一品牌（B1）：演示数据统一挂默认品牌
                    // 种子的 REGIONS 本身就是展示名，regionId 与 regionName 在演示数据里同值
                    p(REGIONS, i), p(REGIONS, i),
                    p(LOCS, i) + ", Dubai, UAE",
                    java.math.BigDecimal.valueOf(5512_0000L + (i * 2_3000L), 6),
                    java.math.BigDecimal.valueOf(2505_0000L + (i * 1_9000L), 6),
                    p(List.of("商场", "机场", "餐饮", "地铁", "写字楼"), i),
                    1 + (i % 4), 2 + (i * 3) % 10, i % 8 == 0 ? "PAUSED" : "ACTIVE"));
        }
        for (int i = 0; i < 30; i++) {
            Site site = sites.get(i % sites.size());
            locations.add(new Location("LOC" + (200 + i),
                    site.name() + " · " + p(List.of("L1东门", "L2中庭", "B1出口", "主入口", "美食广场"), i),
                    site.siteNo(), site.name(), p(List.of("近扶梯", "收银台旁", "入口右侧", "电梯口"), i),
                    1 + (i % 3), i % 9 == 0 ? "PAUSED" : "ACTIVE"));
        }
        for (int i = 0; i < VENUE_NAMES.size(); i++) {
            venues.add(new Venue("VEN" + (300 + i), VENUE_NAMES.get(i), "+9714" + first7(2000000 + i * 311),
                    p(List.of("零售", "航空", "地产", "餐饮"), i), 3 + i * 2));
        }
        List<Double> shareRates = List.of(0.15, 0.2, 0.25, 0.3);
        for (int i = 0; i < 18; i++) {
            contracts.add(new Contract("CT" + (400 + i), p(VENUE_NAMES, i), p(LOCS, i), p(shareRates, i),
                    (i % 4) * 500, iso(i * 30L * 86400_000L), iso(-(365L - i * 10) * 86400_000L),
                    i % 9 == 0 ? "EXPIRED" : "ACTIVE"));
        }

        // —— 财务：分润（12）/ 结算（24）/ 提现（20）/ 分录（60）——
        List<String> payees7 = new ArrayList<>(VENUE_NAMES);
        payees7.add("Agent-North");
        payees7.add("Agent-South");
        List<Double> rate3 = List.of(0.15, 0.2, 0.25);
        for (int i = 0; i < 12; i++) {
            shareRules.add(new ShareRule("SR" + (600 + i), i % 3 == 0 ? "AGENT" : "VENUE", p(payees7, i),
                    i % 4 == 0 ? "CHANNEL_SPLIT" : "LEDGER", p(rate3, i), (i % 3) + 1));
        }
        List<String> payees6 = new ArrayList<>(VENUE_NAMES);
        payees6.add("Agent-North");
        for (int i = 0; i < 24; i++) {
            settlements.add(new Settlement("STL" + (700 + i), i % 3 == 0 ? "AGENT" : "VENUE", p(payees6, i),
                    "2026-" + String.format("%02d", (i % 6) + 1), 800 + (i * 137) % 4000, "AED",
                    p(List.of("GEN", "CONFIRMED", "PAID"), i)));
        }
        for (int i = 0; i < 20; i++) {
            withdrawals.add(new Withdrawal("WD" + (3000 + i), p(payees6, i), 500 + (i * 211) % 3000, "AED",
                    p(List.of("APPLY", "AUDIT", "PAYING", "PAID", "FAILED"), i), iso(i * 43200_000L)));
        }
        List<String> acctsRest = List.of("平台收入", "应付场地方", "应付代理", "押金负债");
        List<String> summaries = List.of("平台分成", "场地方分润", "代理分润", "押金冻结");
        for (int i = 0; i < 60; i++) {
            int pair = i / 2;
            boolean debit = i % 2 == 0;
            double amt = 3 + (pair * 7) % 25;
            ledger.add(new LedgerEntry("LE" + (9000 + i), "V" + (2000 + pair), "ORD" + (500000 + pair),
                    debit ? "现金-nearpay" : p(acctsRest, pair), debit ? "DEBIT" : "CREDIT", amt, "AED",
                    debit ? "收款入账" : p(summaries, pair), iso(i * 1800_000L)));
        }

        // —— 供应商接入（3，deviceCount 按柜机 vendorCode 统计）——
        vendors.add(new Vendor("cd-tech", "CD Technology", "TCP", "ENABLED", null, countByVendor("cd-tech")));
        vendors.add(new Vendor("sd-power", "SD Power", "MQTT", "ENABLED", null, countByVendor("sd-power")));
        vendors.add(new Vendor("chargenow", "ChargeNow Cloud", "HTTP_API", "ENABLED",
                "https://api.chargenow.example", countByVendor("chargenow")));

        // —— C 端用户（60）/ 优惠券（14）——
        for (int i = 0; i < 60; i++) {
            cUsers.add(new CUser("U" + (3000 + i), p(List.of("Ahmed", "Mohammed", "Fatima", "Layla", "Yusuf", "李明"), i),
                    "+9715" + first7(5000000 + i * 173), 550 + (i * 7) % 300, i % 17 == 0, (i * 3) % 40,
                    iso(i * 86400_000L)));
        }
        for (int i = 0; i < 14; i++) {
            double value = i % 2 == 0 ? p(List.of(3.0, 5.0, 10.0), i) : p(List.of(8.0, 9.0), i);
            coupons.add(new Coupon("CP" + (800 + i), p(List.of("新人立减", "满减券", "周末折扣", "会员专享"), i),
                    i % 2 == 0 ? "CUT" : "DISCOUNT", value, (i % 3) * 10, 1000 + i * 100, (i * 137) % 900,
                    i % 6 == 0 ? "PAUSED" : "ACTIVE"));
        }

        // —— 计费模板（4）——
        pricePlans.add(new PricePlan("PP001", "标准（默认）", 5, 30, 3, 30, 60, "AED", "默认", "ACTIVE"));
        pricePlans.add(new PricePlan("PP002", "机场高价", 3, 30, 5, 50, 99, "AED", "机场点位", "ACTIVE"));
        pricePlans.add(new PricePlan("PP003", "商场优惠", 10, 60, 2, 20, 49, "AED", "商场点位", "ACTIVE"));
        pricePlans.add(new PricePlan("PP004", "旧活动价", 15, 30, 2, 20, 40, "AED", "活动", "DISABLED"));

        // —— 代理商（9）——
        List<Double> agentRates = List.of(0.3, 0.35, 0.4);
        for (int i = 0; i < 9; i++) {
            agents.add(new Agent("AG" + String.format("%03d", i + 1),
                    p(List.of("North Hub", "Marina Partner", "Deira Agent", "Airport Ops", "JBR Franchise"), i),
                    "+9715" + first7(6000000 + i * 271), p(REGIONS, i), p(agentRates, i), 4 + i * 3,
                    i % 6 == 0 ? "SUSPENDED" : "ENABLED"));
        }

        // —— 员工（20）/ 角色（7）/ 审计（40）——
        for (int i = 0; i < 20; i++) {
            employees.add(new Employee("E" + (100 + i),
                    p(List.of("Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei", "Fatima N."), i),
                    "+9715" + first7(1000000 + i * 137), p(List.of("运营", "运维", "客服", "财务"), i),
                    p(List.of("运维", "客服", "财务", "租户管理员"), i), i % 11 == 0 ? "LEFT" : "ACTIVE"));
        }
        roles.add(new RoleRow("R1", "ADMIN", "运营管理员", 80, 3, true, "ALL"));
        roles.add(new RoleRow("R2", "OPS", "运维", 22, 12, true, "REGION"));
        roles.add(new RoleRow("R3", "CS", "客服", 16, 6, true, "ALL"));
        roles.add(new RoleRow("R4", "FINANCE", "财务", 20, 4, true, "ALL"));
        roles.add(new RoleRow("R5", "BD", "拓展", 15, 5, true, "REGION"));
        roles.add(new RoleRow("R6", "VIEWER", "只读", 12, 2, true, "ALL"));
        roles.add(new RoleRow("R7", "AGENT", "代理商", 8, 9, true, "AGENT"));
        for (int i = 0; i < 40; i++) {
            audits.add(new AuditEntry("A" + (9000 + i), p(List.of("admin", "ali", "omar", "sara"), i),
                    p(List.of("设备远程弹出", "工单派单", "订单退款", "租户配置修改", "员工新增", "提现审核"), i),
                    p(List.of("CAB1005", "WO70012", "ORD500003", "T10", "E101", "WD3001"), i),
                    "操作成功", "10.165." + (i % 255) + "." + ((i * 7) % 255), iso(i * 1800_000L)));
        }

        // —— 工作台 ——
        int active = (int) cabinets.stream().filter(c -> "ONLINE".equals(c.onlineStatus())).count();
        int open = (int) workOrders.stream().filter(w -> !"CLOSED".equals(w.status()) && !"DONE".equals(w.status())).count();
        List<TrendPoint> trend = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            trend.add(new TrendPoint("D-" + (6 - i), 3000 + (i * 613) % 2500, 250 + (i * 71) % 200));
        }
        dashboard = new DashboardStats(4820, 386, active, active / (double) cabinets.size(), open, "AED", trend);
    }

    private int countByVendor(String code) {
        return (int) cabinets.stream().filter(c -> code.equals(c.vendorCode())).count();
    }

    // —— 只读访问 ——
    public List<Cabinet> cabinets() { return cabinets; }
    public List<RentOrder> orders() { return orders; }
    public List<WorkOrder> workOrders() { return workOrders; }
    public List<Site> sites() { return sites; }
    public List<Location> locations() { return locations; }
    public List<Venue> venues() { return venues; }
    public List<Contract> contracts() { return contracts; }
    public List<ShareRule> shareRules() { return shareRules; }
    public List<Settlement> settlements() { return settlements; }
    public List<Withdrawal> withdrawals() { return withdrawals; }
    public List<LedgerEntry> ledger() { return ledger; }
    public List<Vendor> vendors() { return vendors; }
    public List<CUser> cUsers() { return cUsers; }
    public List<Coupon> coupons() { return coupons; }
    public List<PricePlan> pricePlans() { return pricePlans; }
    public List<Agent> agents() { return agents; }
    public List<Employee> employees() { return employees; }
    public List<RoleRow> roles() { return roles; }
    public List<AuditEntry> audits() { return audits; }
    public DashboardStats dashboard() { return dashboard; }

    public List<Slot> slotsOf(String cabinetNo) {
        Cabinet cab = cabinets.stream().filter(c -> c.cabinetNo().equals(cabinetNo)).findFirst().orElse(null);
        int total = cab == null ? 8 : cab.slotTotal();
        int avail = cab == null ? 0 : cab.availableCount();
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            boolean filled = i < avail;
            String health = (i == total - 1 && cab != null && "FAULT".equals(cab.status())) ? "FAULT" : "OK";
            slots.add(new Slot(i + 1, filled ? "PB" + cabinetNo.substring(3) + (i + 1) : null,
                    filled ? 40 + (i * 13) % 60 : null, filled ? "LOCKED" : "UNLOCKED", health));
        }
        return slots;
    }

    /** 就地替换首个匹配元素（记录不可变，故整体替换）；无匹配则不动。 */
    public <T> void replaceFirst(List<T> list, Predicate<T> match, T value) {
        for (int i = 0; i < list.size(); i++) {
            if (match.test(list.get(i))) {
                list.set(i, value);
                return;
            }
        }
    }
}
