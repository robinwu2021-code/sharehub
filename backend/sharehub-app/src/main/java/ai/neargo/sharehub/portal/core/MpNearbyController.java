package ai.neargo.sharehub.portal.core;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.auth.ConsumerContext;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.loc.LocService;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.platform.sys.service.BizRuleService;
import ai.neargo.sharehub.portal.core.StoreCardAssembler.Quote;
import ai.neargo.sharehub.portal.core.dto.MpNearbyDtos.NearbyCabinetVO;
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
 * <p><b>坐标与距离</b>：{@code loc_site} 的 lat/lng 早已有列也有数据（13 个站点里 12 个有）。
 * 此前这里把三个字段硬编码成 0 并注着「DDL 缺口，待 V31」—— 那条注释在列补上之后没人回来删，
 * 于是<b>地图上每一家店都被钉在 0,0</b>（几内亚湾），而「0 m」在列表里读起来是「你就站在店里」。
 * 现在真出坐标；距离在调用方给了定位时按 haversine 算，<b>算不出就回 null 而不是 0</b>。
 */
@RestController
public class MpNearbyController {

    private static final BigDecimal DEPOSIT_AMOUNT = BigDecimal.valueOf(50);   // 同 RentController 骨架常量

    private final LocService loc;
    private final CabinetService cabinets;
    private final StoreCardAssembler cards;
    private final BizRuleService bizRules;
    private final UserFavoriteService favorites;

    public MpNearbyController(LocService loc, CabinetService cabinets, StoreCardAssembler cards,
                              BizRuleService bizRules, UserFavoriteService favorites) {
        this.loc = loc;
        this.cabinets = cabinets;
        this.cards = cards;
        this.bizRules = bizRules;
        this.favorites = favorites;
    }

    /**
     * 附近机柜（镜像 c-app {@code NearbyCabinet[]}）。{@code returnable=true} 时只出有空仓的。
     *
     * <p>给了 {@code lat}/{@code lng} 就**按距离升序**返回 —— 「附近」这个词要求排序，
     * 不排的话第一屏给的是建站顺序，用户还得自己找哪家最近。没给定位则保持原顺序，
     * 不按 0 排（那等于随机）。
     */
    @GetMapping("/mp/nearby/cabinets")
    public List<NearbyCabinetVO> nearby(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Boolean returnable,
                                        @RequestParam(required = false) Double lat,
                                        @RequestParam(required = false) Double lng) {
        ConsumerContext.require();
        return DataScopeContext.executeWithoutScope(() -> {
            List<NearbyCabinetVO> out = new ArrayList<>();
            for (Site s : loc.pageSites(1, 50, keyword, false).getList()) {
                Quote q = cards.quoteOf(s.siteNo(), s.sceneType());
                for (Cabinet c : cabinets.bySite(s.siteNo())) {
                    int ret = Math.max(0, c.slotTotal() - c.availableCount());
                    if (Boolean.TRUE.equals(returnable) && ret == 0) continue;
                    out.add(cards.card(c, s, q, lat, lng));
                }
            }
            if (lat != null && lng != null) {
                // 算不出距离的排最后：把 null 当 0 会让没坐标的店冒到第一位
                out.sort(java.util.Comparator.comparing(NearbyCabinetVO::distanceM,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
            }
            return out;
        });
    }

    /**
     * 我的收藏（镜像 c-app {@code NearbyCabinet[]}）—— 与找柜列表**同一张卡片、同一个出参**。
     *
     * <p>这个口此前返的是 {@code FavoriteItem}（只有 siteNo/siteName/createdAt），
     * 而收藏页渲染的是门店卡片：地址、可借可还、价格、距离一个都没有，
     * 行键用的 {@code cabinetNo} 还会**全撞在 undefined 上**，点进去是
     * {@code confirm?cabinetNo=undefined}。页面照常渲染，不报错。
     *
     * <p>一个站点有多台机柜时每台出一行，与找柜列表一致 —— 借出是对着机柜发生的。
     */
    @GetMapping("/mp/user/favorites")
    public List<NearbyCabinetVO> myFavorites(@RequestParam(required = false) Double lat,
                                             @RequestParam(required = false) Double lng) {
        String me = ConsumerContext.userNo();
        return DataScopeContext.executeWithoutScope(() -> {
            java.util.Set<String> mine = favorites.pageByOwner(me, 1, 200).getList().stream()
                    .map(f -> f.siteNo()).collect(java.util.stream.Collectors.toSet());
            List<NearbyCabinetVO> out = new ArrayList<>();
            if (mine.isEmpty()) return out;
            for (Site s : loc.pageSites(1, 200, null, false).getList()) {
                if (!mine.contains(s.siteNo())) continue;
                Quote q = cards.quoteOf(s.siteNo(), s.sceneType());
                for (Cabinet c : cabinets.bySite(s.siteNo())) {
                    out.add(cards.card(c, s, q, lat, lng));
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
            Quote q = cards.quoteOf(s == null ? null : s.siteNo(), s == null ? null : s.sceneType());
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
    public Map<String, Object> site(@PathVariable String siteNo,
                                    @RequestParam(required = false) Double lat,
                                    @RequestParam(required = false) Double lng) {
        String me = ConsumerContext.userNo();
        return DataScopeContext.executeWithoutScope(() -> {
            Site s = loc.pageSites(1, 200, null, false).getList().stream()
                    .filter(x -> siteNo.equals(x.siteNo())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("站点不存在: " + siteNo));
            List<Cabinet> cs = cabinets.bySite(siteNo);
            int borrow = cs.stream().mapToInt(Cabinet::availableCount).sum();
            int ret = cs.stream().mapToInt(c -> Math.max(0, c.slotTotal() - c.availableCount())).sum();
            Quote q = cards.quoteOf(siteNo, s.sceneType());
            boolean fav = favorites.pageByOwner(me, 1, 200).getList().stream()
                    .anyMatch(f -> siteNo.equals(f.siteNo()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("siteNo", s.siteNo());
            out.put("siteName", s.name());
            out.put("address", s.address());
            // 三个字段原先硬编码成空/0，注着「DDL 缺口」—— 列早就补上了，注释没人回来删。
            // open_hours 目前 13 个站点全为空，但那是**数据没录**，运营录完这里就该显示，
            // 而写死 "" 的话录了也永远不显示。
            out.put("openHours", s.openHours() == null ? "" : s.openHours());
            out.put("lat", s.lat());
            out.put("lng", s.lng());
            out.put("distanceM", StoreCardAssembler.distanceM(lat, lng, s.lat(), s.lng()));
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

    private Site siteOf(Cabinet c) {
        return loc.pageSites(1, 200, null, false).getList().stream()
                .filter(x -> x.siteNo() != null && x.siteNo().equals(c.siteNo()))
                .findFirst().orElse(null);
    }

}
