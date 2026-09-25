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
    /**
     * 广告活动**写入参**（白名单）。
     *
     * <p><b>比实体少一个 {@code status}</b> —— 只能由
     * {@code POST /api/user/ad-campaigns/{adNo}/{action}} 走状态机改。
     * 广告要对广告主结算、暂停即停止计费，状态更不能绕着改。
     *
     * <p>不声明是白名单，比在 service 里加锁（黑名单）更靠得住：
     * 锁得有人记得写，不声明则新人照抄也漏不掉。
     *
     * @param advertiserNo 广告主编号，必填 —— {@code ad_campaign.advertiser_no} 是
     *                     NOT NULL 无默认，不校验的话落库时撞约束，
     *                     返回的是 <b>500 而不是 400</b>（2026-09-25 实测）
     */
    public record AdCampaignReq(String adNo, String advertiserNo, String advertiser,
                                String creative, java.math.BigDecimal budget, String currency,
                                String targeting, String startAt, String endAt) {
        /** 映射到实体。**status 有意不设**（见类注释）。 */
        public ai.neargo.sharehub.user.ad.entity.AdCampaign toEntity() {
            if (advertiserNo == null || advertiserNo.isBlank()) {
                throw new IllegalArgumentException("广告主编号必填");
            }
            var e = new ai.neargo.sharehub.user.ad.entity.AdCampaign();
            e.setAdNo(adNo);
            e.setAdvertiserNo(advertiserNo);
            e.setAdvertiser(advertiser);
            e.setCreative(creative);
            e.setBudget(budget);
            e.setCurrency(currency);
            e.setTargeting(targeting);
            e.setStartAt(startAt);
            e.setEndAt(endAt);
            return e;
        }
    }

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
