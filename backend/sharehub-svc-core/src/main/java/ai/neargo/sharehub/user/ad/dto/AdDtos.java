package ai.neargo.sharehub.user.ad.dto;

import java.math.BigDecimal;

/**
 * user/ad 子域出参 VO。字段镜像 ops-web {@code lib/types/marketing.ts}
 * （{@code AdSlot} / {@code AdCampaign} / {@code AdDelivery}）。
 */
public final class AdDtos {

    private AdDtos() {
    }

    /** 广告位行，镜像前端 {@code AdSlot}。 */
    public record AdSlotVO(String slotNo, String cabinetNo, String position, String size,
                           String status, String createdAt) {
    }

    /** 广告主行。 */
    public record AdvertiserVO(String advertiserNo, String name, String contact) {
    }

    /** 广告活动行，镜像前端 {@code AdCampaign}（多出 budget/currency/advertiserNo 供运营核对）。 */
    public record AdCampaignVO(String adNo, String advertiserNo, String advertiser, String creative,
                               BigDecimal budget, String currency, String targeting,
                               String status, String startAt, String endAt) {
    }

    /** 广告创意行。 */
    public record AdCreativeVO(String creativeNo, String adNo, String mediaUrl,
                               Integer duration, String mime) {
    }

    /** 投放排期行。 */
    public record AdPlacementVO(String placementNo, String adNo, String creativeNo, String slotNo,
                                String schedule, String status) {
    }

    /**
     * 投放曝光统计行，镜像前端 {@code AdDelivery}（前端字段名是 {@code date}，此处保持一致）。
     * 来自 append 表 {@code ad_impression}，只读。
     */
    public record AdDeliveryVO(String deliveryNo, String adNo, String slotNo, String cabinetNo,
                               Integer impressions, Integer plays, String date) {
    }
}
