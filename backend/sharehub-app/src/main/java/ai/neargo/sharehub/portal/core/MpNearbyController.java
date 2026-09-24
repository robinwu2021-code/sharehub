package ai.neargo.sharehub.portal.core;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.auth.ConsumerContext;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.loc.LocService;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.platform.sys.service.BizRuleService;
import ai.neargo.sharehub.trade.price.engine.PriceItemSpec;
import ai.neargo.sharehub.trade.price.engine.PriceQuery;
import ai.neargo.sharehub.trade.price.engine.PriceResolver;
import ai.neargo.sharehub.user.core.service.UserFavoriteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C 端找柜 BFF（{@code /mp/nearby/**} + {@code /mp/sites/{siteNo}}，需 CONSUMER 会话）。
 * 组合 loc（站点）× dev（机柜/仓位）× price（报价）三个域的读，portal 是唯一组合点。
 *
 * <p><b>数据范围显式豁免</b>：C 端会话的 spec 是 SELF，而 {@code loc_site}/{@code dev_cabinet}
 * 没有（也不该有）{@code c_user_no} 锚点 —— 这里按 {@code DataScopeRegistration} 注释的既定方案
 * 走 {@code executeWithoutScope}，不给运营表编假 SELF 锚点。
 *
 * <p><b>⚠️ 距离与坐标是缺口</b>：{@code loc_site} 无 lat/lng 列（DDL 缺口，待 V31），
 * {@code distanceM}/{@code lat}/{@code lng} 如实出 0，端上按无定位渲染；不伪造距离排序。
 */
@RestController
public class MpNearbyController {

    private static final BigDecimal DEPOSIT_AMOUNT = BigDecimal.valueOf(50);   // 同 RentController 骨架常量

    private final LocService loc;
    private final CabinetService cabinets;
    private final PriceResolver prices;
    private final BizRuleService bizRules;
    private final UserFavoriteService favorites;

    public MpNearbyController(LocService loc, CabinetService cabinets, PriceResolver prices,
                              BizRuleService bizRules, UserFavoriteService favorites) {
        this.loc = loc;
        this.cabinets = cabinets;
        this.prices = prices;
        this.bizRules = bizRules;
        this.favorites = favorites;
    }

    /** 附近机柜（镜像 c-app {@code NearbyCabinet[]}）。{@code returnable=true} 时只出有空仓的。 */
    @GetMapping("/mp/nearby/cabinets")
    public List<Map<String, Object>> nearby(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Boolean returnable,
                                            @RequestParam(required = false) Double lat,
                                            @RequestParam(required = false) Double lng) {
        ConsumerContext.require();
        return DataScopeContext.executeWithoutScope(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Site s : loc.pageSites(1, 50, keyword, false).getList()) {
                Quote q = quoteOf(s.siteNo(), s.sceneType());
                for (Cabinet c : cabinets.bySite(s.siteNo())) {
                    int borrow = c.availableCount();
                    int ret = Math.max(0, c.slotTotal() - c.availableCount());
                    if (Boolean.TRUE.equals(returnable) && ret == 0) continue;
                    out.add(nearbyRow(c, s, borrow, ret, q));
                }
            }
            return out;
        });
    }

    /** 借出可用性校验（借出确认页，镜像 c-app {@code CabinetAvailability}）。 */
    @GetMapping("/mp/nearby/cabinets/{cabinetNo}/availability")
    public Map<String, Object> availability(@PathVariable String cabinetNo) {
        ConsumerContext.require();
        return DataScopeContext.executeWithoutScope(() -> {
            Cabinet c = cabinets.detail(cabinetNo).cabinet();
            Site s = siteOf(c);
            Quote q = quoteOf(s == null ? null : s.siteNo(), s == null ? null : s.sceneType());
            var billing = bizRules.get() == null ? null : bizRules.get().billing();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cabinetNo", c.cabinetNo());
            out.put("siteName", s == null ? c.locationName() : s.name());
            out.put("borrowable", c.availableCount() > 0 && "ONLINE".equals(c.onlineStatus())
                    && !"FAULT".equals(c.status()));
            out.put("pricePerHour", q.perHour());
            out.put("dailyCap", q.dailyCap());
            out.put("buyoutPrice", billing == null || billing.buyoutPrice() == null
                    ? BigDecimal.ZERO : billing.buyoutPrice());
            out.put("depositAmount", DEPOSIT_AMOUNT);
            out.put("freeQuota", DEPOSIT_AMOUNT);
            out.put("currency", q.currency());
            return out;
        });
    }

    /** 门店详情（镜像 c-app {@code StoreDetail}）。 */
    @GetMapping("/mp/sites/{siteNo}")
    public Map<String, Object> site(@PathVariable String siteNo) {
        String me = ConsumerContext.userNo();
        return DataScopeContext.executeWithoutScope(() -> {
            Site s = loc.pageSites(1, 200, null, false).getList().stream()
                    .filter(x -> siteNo.equals(x.siteNo())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("站点不存在: " + siteNo));
            List<Cabinet> cs = cabinets.bySite(siteNo);
            int borrow = cs.stream().mapToInt(Cabinet::availableCount).sum();
            int ret = cs.stream().mapToInt(c -> Math.max(0, c.slotTotal() - c.availableCount())).sum();
            Quote q = quoteOf(siteNo, s.sceneType());
            boolean fav = favorites.pageByOwner(me, 1, 200).getList().stream()
                    .anyMatch(f -> siteNo.equals(f.siteNo()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("siteNo", s.siteNo());
            out.put("siteName", s.name());
            out.put("address", s.address());
            out.put("openHours", "");            // loc_site 无营业时段列（DDL 缺口，与 lat/lng 同批补）
            out.put("lat", 0);
            out.put("lng", 0);
            out.put("distanceM", 0);
            out.put("availableBorrow", borrow);
            out.put("availableReturn", ret);
            out.put("pricePerHour", q.perHour());
            out.put("currency", q.currency());
            out.put("favorite", fav);
            out.put("cabinets", cs.stream().map(c -> Map.of(
                    "cabinetNo", c.cabinetNo(),
                    "borrow", c.availableCount(),
                    "return", Math.max(0, c.slotTotal() - c.availableCount()))).toList());
            return out;
        });
    }

    // ——————————————————————— 组装 ———————————————————————

    private Map<String, Object> nearbyRow(Cabinet c, Site s, int borrow, int ret, Quote q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cabinetNo", c.cabinetNo());
        m.put("siteNo", s.siteNo());
        m.put("siteName", s.name());
        m.put("address", s.address());
        m.put("distanceM", 0);
        m.put("lat", 0);
        m.put("lng", 0);
        m.put("availableBorrow", borrow);
        m.put("availableReturn", ret);
        m.put("pricePerHour", q.perHour());
        m.put("currency", q.currency());
        m.put("status", "ACTIVE".equals(s.status()) ? "ACTIVE" : "PAUSED");
        return m;
    }

    /**
     * 站点报价：解析该站点生效计价方案的时间项 → 每小时单价 + 日封顶。
     *
     * <p>C 端列表只知道站点与场景 —— 机柜、点位、代理、厂商这些更具体的层在这里无从判断，
     * 对应引用留 {@code null}，那几层就不参与匹配（{@link PriceQuery} 的约定：
     * null = 无从判断，不是通配）。所以列表价是**站点级的展示价**，
     * 真正的成单价以借出时按具体机柜解析的快照为准。
     */
    private Quote quoteOf(String siteNo, String sceneType) {
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

    private Site siteOf(Cabinet c) {
        return loc.pageSites(1, 200, null, false).getList().stream()
                .filter(x -> x.siteNo() != null && x.siteNo().equals(c.siteNo()))
                .findFirst().orElse(null);
    }

    private record Quote(BigDecimal perHour, BigDecimal dailyCap, String currency) {
    }
}
