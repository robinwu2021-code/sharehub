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

    public StoreCardAssembler(PriceResolver prices) {
        this.prices = prices;
    }

    /**
     * 这台机柜此刻能不能借出。**找柜列表与机柜详情必须用同一个判断**。
     *
     * <p>2026-09-27 H5 对接实测：CAB1013 是 {@code ONLINE} 但 {@code status=FAULT}，
     * 于是首页列表显示「可借 1」（它只看 {@code availableCount}），
     * 而 {@code /mp/nearby/cabinets/{no}/availability} 返 {@code borrowable:false}
     * （它判了在线与故障）。<b>用户走到店里才发现借不了。</b>
     *
     * <p>两处各写一遍判断是这类分叉的通用形状：两边单看都对，合起来对不上，
     * 而没有任何东西会报错。收成这一个方法，两边都调它。
     */
    public static boolean borrowable(Cabinet c) {
        return c.availableCount() > 0
                && "ONLINE".equals(c.onlineStatus())
                && !"FAULT".equals(c.status());
    }

    public NearbyCabinetVO card(Cabinet c, Site s, Quote q, Double fromLat, Double fromLng) {
        boolean ok = borrowable(c);
        // 借不了就出 0：仓里还有宝，但这台机柜给不出来 —— 报真实台数会让用户白跑一趟
        int borrow = ok ? c.availableCount() : 0;
        int ret = Math.max(0, c.slotTotal() - c.availableCount());
        /*
         * status 此前只映射站点状态。机柜本身离线/故障时也要出 PAUSED ——
         * 否则这张卡片会显示成「满仓」（端上 borrow=0 就渲染 Full），
         * 而「满仓」与「这台坏了」对用户是两件事：前者换个柜子还，后者这家店白来。
         */
        String status = ("ACTIVE".equals(s.status()) && ok) ? "ACTIVE" : "PAUSED";
        return new NearbyCabinetVO(
                c.cabinetNo(), s.siteNo(), s.name(), s.address(),
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
        return new Quote(perHour, dailyCap, r.currency() == null ? "AED" : r.currency());
    }

    public record Quote(BigDecimal perHour, BigDecimal dailyCap, String currency) {
    }
}
