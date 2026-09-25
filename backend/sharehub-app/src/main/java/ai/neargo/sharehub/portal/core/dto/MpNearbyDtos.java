package ai.neargo.sharehub.portal.core.dto;

import java.math.BigDecimal;

/**
 * C 端找柜 BFF 的出参（{@code /mp/nearby/**}、{@code /mp/user/favorites}）。
 *
 * <p><b>为什么要有具名 record，而不是继续返 {@code Map<String,Object>}</b>：
 * 对齐脚本按**出参类型**比字段，返 Map 的端点 {@code responseShape} 是空的 ——
 * 于是整条找柜链路从来没有被比对过。实测代价就在下面那两行注释里：
 * 坐标一直在返 0，没有任何卡口能发现。
 */
public final class MpNearbyDtos {

    private MpNearbyDtos() {
    }

    /**
     * 门店卡片（镜像 c-app {@code NearbyCabinet}）—— 找柜列表与收藏列表用的是同一张卡片，
     * 所以也是同一个出参形状。两处各摆一份的话，字段迟早分叉，而分叉的表现是
     * 「同一家店在两个页面上显示的可借数不一样」。
     *
     * @param distanceM 到调用方的直线距离（米）。<b>{@code null} = 没法算</b> ——
     *                  调用方没给定位，或这个站点没有坐标。
     *                  <b>不要回 0</b>：0 在界面上会被渲染成「0 m」，即「你就站在店里」。
     * @param lat       站点纬度；站点没录坐标时为 null。<b>同样不要回 0</b> ——
     *                  0,0 是几内亚湾那个点，地图会把所有没坐标的店都钉在非洲西边。
     */
    public record NearbyCabinetVO(String cabinetNo, String siteNo, String siteName, String address,
                                  Integer distanceM, BigDecimal lat, BigDecimal lng,
                                  int availableBorrow, int availableReturn,
                                  BigDecimal pricePerHour, String currency, String status) {
    }
}
