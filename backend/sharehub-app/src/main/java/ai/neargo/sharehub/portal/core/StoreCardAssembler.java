package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.portal.core.dto.MpNearbyDtos.NearbyCabinetVO;
import ai.neargo.sharehub.trade.price.engine.PriceItemSpec;
import ai.neargo.sharehub.trade.price.engine.PriceQuery;
import ai.neargo.sharehub.trade.price.engine.PriceResolver;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 门店卡片的组装（站点 × 机柜 × 报价 → {@link NearbyCabinetVO}）。
 *
 * <p><b>抽出来是因为它有两个调用方</b>：找柜列表与收藏列表渲染的是同一张卡片。
 * 各组装一份的后果不是重复代码，是**分叉** —— 同一家店在两个页面上显示的可借数、
 * 价格、甚至距离口径会慢慢不一样，而这种不一致没有任何测试会红。
 */
@Component
public class StoreCardAssembler {

    /** 地球平均半径（米），haversine 用。 */
    private static final double EARTH_RADIUS_M = 6_371_000d;

    private final PriceResolver prices;
    private final ai.neargo.sharehub.trade.RentabilityGuard rentability;

    public StoreCardAssembler(PriceResolver prices,
                              ai.neargo.sharehub.trade.RentabilityGuard rentability) {
        this.prices = prices;
        this.rentability = rentability;
    }

    /**
     * 这台机柜此刻能不能借出。**找柜列表与机柜详情必须用同一个判断**。
     *
     * <p>2026-09-27 H5 对接实测：CAB1013 是 {@code ONLINE} 但 {@code status=FAULT}，
     * 于是首页列表显示「可借 1」（它只看 {@code availableCount}），
     * 而 {@code /mp/nearby/cabinets/{no}/availability} 返 {@code borrowable:false}
     * （它判了在线与故障）。<b>用户走到店里才发现借不了。</b>
     *
     * <p><b>而且原来是三处各写一遍</b>：列表只看台数、详情看「在线且非故障」、
     * 真正的借出走 {@code RentabilityGuard}（站点可租 + DEPLOYED + 在线 + 未整柜停借 + 有货）。
     * 于是停用站点在首页列着「可借 7」，点进去借才撞 409「该站点暂停服务」——
     * 三处各自都说得通，合起来是用户白跑一趟。
     *
     * <p>所以这里**不再自己判**，直接问那个权威守卫：判据只有一份，
     * 展示层取布尔、借出取异常，是同一份判断的两个视图。
     */
    public boolean borrowable(Cabinet c) {
        return blockedReason(c).isEmpty();
    }

    /** 借不了的原因（i18n key），能借则空。来源是权威守卫，本类不自己判。 */
    private java.util.Optional<String> blockedReason(Cabinet c) {
        return rentability.blockedReason(c.cabinetNo(), c.siteNo(), c.status(),
                c.onlineStatus(), c.availableCount());
    }

    /**
     * 「没货」不算停用。
     *
     * <p>守卫把 {@code no_stock} 也算进「借不了」—— 对借出那条路是对的（确实借不了），
     * 但对卡片不是：**没货是满仓，停用是这家店今天不营业**，对用户是两件事，
     * 前者换个时间或换台柜子，后者别来。
     *
     * <p>第一版把两者一起标成 PAUSED，结果 48 张卡里 22 张显示「暂停服务」——
     * 而实际停用的只有 11 台（4 台故障 + 7 台在停用站点），另外 11 台只是空了。
     */
    private static final String NO_STOCK = "error.rent.no_stock";

    /**
     * 一家店一张卡：把站点下所有机柜汇总成一张（出参形状与单柜卡相同）。
     *
     * <p>此前找柜列表与收藏页都是「一台柜一行」，卡上只有店名 —— 测试库里连着 5～7 张一模一样的
     * 「Dubai Mall L1」，用户分不出哪张是哪张；收藏是按站点存的，收藏一家店出 7 张卡。
     * （2026-09-27 产品拍板：两处都按站点合并。）
     *
     * <ul>
     *   <li><b>可借</b>只算真能借的柜（{@link #borrowable}），与门店卡 {@code /mp/sites/{no}} 同一个数；</li>
     *   <li><b>可还</b>各柜空仓相加，同门店卡；</li>
     *   <li><b>cabinetNo</b> 填「借」该去的那台：能借的柜里宝最多的；都借不了就给第一台 ——
     *       行键不能空，借出确认页会说清楚为什么借不了；</li>
     *   <li><b>status</b>：站点营业且至少一台柜在服务（能借或只是没货）才是 ACTIVE。
     *       机柜离线/故障要算进 PAUSED —— 否则卡片显示成「满仓」，而「满仓」与「坏了」对用户是两件事：
     *       前者换个柜子还，后者这家店白来。</li>
     *   <li>借不了的柜不计可借：仓里还有宝，但这台给不出来，报真实台数会让用户白跑一趟。</li>
     * </ul>
     *
     * @return 站点下没有机柜时为 {@code null}（列表跳过这家店）
     */
    public NearbyCabinetVO siteCard(java.util.List<Cabinet> cs, Site s, Quote q, Double fromLat, Double fromLng) {
        if (cs == null || cs.isEmpty()) return null;
        Cabinet pick = null;
        int borrow = 0, ret = 0;
        boolean anyServing = false;
        for (Cabinet c : cs) {
            java.util.Optional<String> blocked = blockedReason(c);
            ret += Math.max(0, c.slotTotal() - c.availableCount());
            if (blocked.isEmpty()) {
                borrow += c.availableCount();
                if (pick == null || c.availableCount() > pick.availableCount()) pick = c;
            }
            if (blocked.isEmpty() || NO_STOCK.equals(blocked.orElse(null))) anyServing = true;
        }
        if (pick == null) pick = cs.get(0);
        String status = ("ACTIVE".equals(s.status()) && anyServing) ? "ACTIVE" : "PAUSED";
        return new NearbyCabinetVO(
                pick.cabinetNo(), s.siteNo(), s.name(), s.address(),
                distanceM(fromLat, fromLng, s.lat(), s.lng()),
                s.lat(), s.lng(),
                borrow, ret,
                q.perHour(), q.currency(),
                status);
    }

    /**
     * 直线距离（米）。任一端缺坐标就返回 {@code null} —— 「算不出」和「0 米」是两回事，
     * 而界面把 0 渲染成「0 m」。
     */
    public static Integer distanceM(Double fromLat, Double fromLng, BigDecimal toLat, BigDecimal toLng) {
        if (fromLat == null || fromLng == null || toLat == null || toLng == null) return null;
        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat.doubleValue());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(toLng.doubleValue() - fromLng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return (int) Math.round(EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    /**
     * 站点报价：解析该站点生效计价方案的时间项 → 每小时单价 + 日封顶。
     *
     * <p>C 端列表只知道站点与场景 —— 机柜、点位、代理、厂商这些更具体的层在这里无从判断，
     * 对应引用留 {@code null}，那几层就不参与匹配（{@link PriceQuery} 的约定：
     * null = 无从判断，不是通配）。所以列表价是**站点级的展示价**，
     * 真正的成单价以借出时按具体机柜解析的快照为准。
     */
    public Quote quoteOf(String siteNo, String sceneType) {
        PriceResolver.Resolved r = prices.resolve(new PriceQuery(
                "POWERBANK", null, null, siteNo, null, null, sceneType, null,
                null, null, null, java.time.LocalDateTime.now()));
        BigDecimal perHour = BigDecimal.ZERO;
        BigDecimal dailyCap = BigDecimal.ZERO;
        for (PriceItemSpec spec : r.specs()) {
            if (spec.ladders() == null || spec.ladders().isEmpty()) continue;
            BigDecimal unitQty = spec.unitQty() == null || spec.unitQty().signum() == 0
                    ? BigDecimal.valueOf(60) : spec.unitQty();
            BigDecimal unitPrice = spec.ladders().get(0).unitPrice();
            if (unitPrice == null) continue;
            perHour = unitPrice.multiply(BigDecimal.valueOf(60))
                    .divide(unitQty, 2, RoundingMode.HALF_UP);
            dailyCap = spec.capDaily() == null ? BigDecimal.ZERO : spec.capDaily();
            break;   // 取首个时间计费项；多项叠加的复杂方案在借出确认页由 PriceEngine 精算
        }
        return new Quote(perHour, dailyCap, r.currency() == null ? "AED" : r.currency(), r.capTotal(), r.lostCharge());
    }

    /**
     * @param maxCharge  最高租赁收费 = 方案总封顶；null = 方案没配（不封顶）
     * @param lostCharge 不还时收多少 = 独立遗失费，没配则等于 maxCharge（V180）
     */
    public record Quote(BigDecimal perHour, BigDecimal dailyCap, String currency,
                        BigDecimal maxCharge, BigDecimal lostCharge) {
    }
}
