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

    public NearbyCabinetVO card(Cabinet c, Site s, Quote q, Double fromLat, Double fromLng) {
        int borrow = c.availableCount();
        int ret = Math.max(0, c.slotTotal() - c.availableCount());
        return new NearbyCabinetVO(
                c.cabinetNo(), s.siteNo(), s.name(), s.address(),
                distanceM(fromLat, fromLng, s.lat(), s.lng()),
                s.lat(), s.lng(),
                borrow, ret,
                q.perHour(), q.currency(),
                "ACTIVE".equals(s.status()) ? "ACTIVE" : "PAUSED");
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
