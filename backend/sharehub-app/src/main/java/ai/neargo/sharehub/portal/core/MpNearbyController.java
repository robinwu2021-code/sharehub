package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.auth.ConsumerContext;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.loc.LocService;
import ai.neargo.sharehub.loc.SiteStatus;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MpNearbyController.class);

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
        // **游客可看**（C 端功能清单 §一：游客「可浏览附近网点/价格；扫码借出前强制登录」）。
        // 此前这里强制要求登录（ConsumerContext#require），与 SecurityConfig 对 /mp/nearby/** 的放行打架：
        // （⚠️ 注释里别写出「ConsumerContext」加点号的字样：MpOwnershipContractTest 按文本扫方法体，
        //   一句注释就能让它以为这个口经过了收口 —— 09-27 实测撞到过。）
        // 安全链放进来了，控制器又拒回去 —— 游客开 App 首页直接 403，会话守卫把他送去登录页。
        // 借出本身仍然要登录（/mp/trade/** 不放行），这里只是看。
        return DataScopeContext.executeWithoutScope(() -> {
            List<NearbyCabinetVO> out = new ArrayList<>();
            for (Site s : loc.pageSites(1, 50, keyword, false).getList()) {
                if (!consumerVisible(s)) continue;
                Quote q = quoteOrSkip(s);
                if (q == null) continue;                      // 见 quoteOrSkip：算不出价就不上榜
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
     * 列表用的报价：算不出就回 {@code null} 让调用方跳过这家店，**而不是让整张列表挂掉**。
     *
     * <p>2026-09-27 H5 对接实测：{@code /mp/nearby/cabinets} 恒返 409
     * 「该点位暂不可借，请到附近其它网点试试」—— C 端首页的主数据源整条不通。
     * 成因是 {@code StoreCardAssembler#quoteOf} 里的 {@code PriceResolver.resolve}
     * 在站点匹配不到计价方案时抛 409，而这里在**循环里**调它、没有兜底：
     * <b>13 家店里只要有一家没配价，另外 12 家也一起消失。</b>
     *
     * <p>那个 409 本身是对的 —— 对**站在柜机前准备借**的人，「这儿借不了，换一家」
     * 正是他能行动的那句话。错的是把它用在列表上：列表的读者不在任何一家店门口，
     * 他要的是「还有哪些店」。所以同一个失败，**单条查询保留 409，列表逐行降级**。
     *
     * <p><b>为什么是跳过而不是标成 PAUSED</b>：出参里确实有 {@code status} 这一位，
     * 但 c-app 全仓**没有任何一处读它**（已 grep），标了没有任何视觉效果，
     * 而价格会渲染成 {@code AED 0/h} —— 读起来是「免费」。
     * 在 c-app 真按 status 渲染之前，不显示比显示成免费诚实。
     *
     * <p>WARN 带站点号：这是运维能直接行动的一条（给该站点配价，或配一条 ALL 层默认方案）。
     */
    private Quote quoteOrSkip(Site s) {
        try {
            return cards.quoteOf(s.siteNo(), s.sceneType());
        } catch (BizException e) {
            log.warn("站点算不出报价，已从列表中略过 siteNo={} sceneType={} reason={} —— "
                            + "请为该站点配置计价方案，或配一条 ALL 层默认方案",
                    s.siteNo(), s.sceneType(), e.getMessage());
            return null;
        }
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
                if (!consumerVisible(s)) continue;   // 收藏了一家后来关掉的店，不该还挂在列表上
                Quote q = quoteOrSkip(s);
                if (q == null) continue;                      // 同找柜列表：一家店算不出价不该让整个收藏夹空掉
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
        // 这一口**仍要登录**（与上面的找柜不同）：它只服务借出确认页，而进确认页之前
        // 端上已有登录门禁；价格游客在找柜列表与门店卡上就看得到。
        // 路径里带标识的 /mp 接口一律经 ConsumerContext（MpOwnershipContractTest，无例外）——
        // 为一个游客用不到的口子给那道卡口开例外，不划算。
        ConsumerContext.require();
        return DataScopeContext.executeWithoutScope(() -> {
            Cabinet c = cabinets.detail(cabinetNo).cabinet();
            Site s = siteOf(c);
            Quote q = cards.quoteOf(s == null ? null : s.siteNo(), s == null ? null : s.sceneType());
            var billing = bizRules.get() == null ? null : bizRules.get().billing();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cabinetNo", c.cabinetNo());
            out.put("siteName", s == null ? c.locationName() : s.name());
            // 与找柜列表共用同一个判断（见 StoreCardAssembler#borrowable）——
            // 两处各写一遍，就会出现「列表说可借、详情说不可借」，而两边单看都对。
            out.put("borrowable", cards.borrowable(c));
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
        // 游客也能看门店卡（点地图上的点就弹这个）；登录了才有「已收藏」这一位
        String me = ConsumerContext.optionalUserNo();
        return DataScopeContext.executeWithoutScope(() -> {
            Site s = loc.pageSites(1, 200, null, false).getList().stream()
                    .filter(x -> siteNo.equals(x.siteNo())).findFirst()
                    .orElseThrow(() -> BizException.notFound(siteNo));
            List<Cabinet> cs = cabinets.bySite(siteNo);
            /*
             * 可借数只算**真能借的柜**（与找柜列表、借出可用性同一个判法 StoreCardAssembler#borrowable）。
             *
             * 此前是所有柜的 availableCount 直接相加 —— 离线 / 故障 / 停用的柜里的宝也算进去。
             * 模拟器实测：门店卡写「可借 29」，列表里这家店的每台柜都是「不可用」；
             * 用户照着门店卡点「借」，进的是一台借不了的柜。这是「同一个事实两个来源」的第三处
             * （前两处 1f690b2 已统一：列表与借出确认页），当时漏了门店卡。
             */
            int borrow = cs.stream().filter(cards::borrowable).mapToInt(Cabinet::availableCount).sum();
            int ret = cs.stream().mapToInt(c -> Math.max(0, c.slotTotal() - c.availableCount())).sum();
            Quote q = cards.quoteOf(siteNo, s.sceneType());
            boolean fav = me != null && favorites.pageByOwner(me, 1, 200).getList().stream()
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

    /**
     * 这个站点该不该出现在 C 端。
     *
     * <p>站点生命周期 2026-09-25 扩成五态（PREPARING / ACTIVE / PAUSED / WITHDRAWING / CLOSED），
     * 而本控制器原先**不按状态过滤** —— 于是筹备中和已关闭的站点会一起列进找柜结果，
     * 用户点进去才发现借不了。实测那次改动落地后，库里 39 个站点有 11 个 PREPARING、1 个 CLOSED。
     *
     * <p><b>PAUSED 与 WITHDRAWING 保留</b>：它们的语义是「停借保还」——
     * 手里有充电宝的人正需要看到这些点位才能还回去。出参里的 status 会是 PAUSED，
     * 端上据此置灰「借」而保留「还」。
     * <b>PREPARING / CLOSED 直接不出</b>：前者还没开业、后者已经没了，列出来只会浪费一次点击。
     */
    private static boolean consumerVisible(Site s) {
        String st = s.status();
        return !SiteStatus.PREPARING.name().equals(st) && !SiteStatus.CLOSED.name().equals(st);
    }

    // ——————————————————————— 组装 ———————————————————————

    private Site siteOf(Cabinet c) {
        return loc.pageSites(1, 200, null, false).getList().stream()
                .filter(x -> x.siteNo() != null && x.siteNo().equals(c.siteNo()))
                .findFirst().orElse(null);
    }

}
