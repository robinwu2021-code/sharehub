package ai.neargo.sharehub.loc.ext.dto;

import java.math.BigDecimal;

/**
 * loc/ext 子域出参 VO 与动作入参。
 *
 * <p><b>约定</b>（[骨架规约 §4]）：域内 dto 文件，**不往顶层 {@code dto/Dto.java} 追加**
 * —— 那个文件是早期骨架的共享 DTO 集合，已冻结。字段镜像
 * {@code ops-web/lib/types/location.ts} 的同名 interface。
 */
public final class LocExtDtos {

    private LocExtDtos() {
    }

    /** BD 商机行，镜像前端 {@code Lead}。 */
    /**
     * @param owner     归属方业务号；{@code ownerType} 说它是 employee_no 还是 agent_no
     * @param ownerType STAFF / AGENT（V55）
     * @param siteNo    最终落成的站点；签下且归属是伙伴时，据此写 DEVELOP 责任行
     */
    public record Lead(String leadNo, String venueName, String contact, String stage,
                       String owner, String ownerType, String siteNo,
                       Integer expectSites, String nextFollowAt,
                       String updatedAt,
                       // 批次 B（V104）
                       String address, String venueNo, String contractNo, String lostReason,
                       java.time.LocalDateTime lostAt, java.time.LocalDateTime lastFollowAt, boolean inPool,
                       String prevOwner, String competitorName, java.time.LocalDate competitorExclusiveUntil,
                       java.time.LocalDateTime reactivatedAt, LeadTerms terms) {
    }

    /** 商机上的谈判条款：签约转化时带进合同草稿。 */
    public record LeadTerms(String shareMode, BigDecimal shareRate, BigDecimal entryFee, BigDecimal guaranteeAmount,
                            Integer termMonths, Boolean exclusive) {
    }

    /**
     * 签约转化入参（对齐清单 B8 · E1）。全部可空：空的从商机上取（场地方名 / 地址 / 区域 / 谈判条款）。
     *
     * @param venueNo 关联已有场地方；空则按商机的场地名新建
     * @param siteNo  关联已有站点（须属于该场地方）；空则新建一个筹备中的站点
     */
    public record LeadConvertReq(String venueNo, String siteNo, String siteName, String regionId, String address,
                                 String openHours, String shareMode, BigDecimal shareRate, BigDecimal entryFee,
                                 BigDecimal guaranteeAmount, java.time.LocalDate startAt, Integer termMonths,
                                 Boolean exclusive) {
    }

    public record LeadConversion(String leadNo, String venueNo, boolean venueCreated, String siteNo, boolean siteCreated,
                                 String contractNo) {
    }

    public record LeadTickResult(int reminded, int pooled, int reactivated) {
    }

    /** 门店进件行，镜像前端 {@code VenueOnboarding}（+ {@code venueNo}：通过后回填的场地方号）。 */
    public record VenueOnboarding(String onboardingNo, String venueName, String contact,
                                  String industry, String requestedAt, String status,
                                  String reviewAt, String reviewNote, String venueNo) {
    }

    /**
     * 进件审核入参。{@code approve=true} 时服务端会先建 {@code loc_venue} 再回填 {@code venueNo}。
     * {@code approve=false}（驳回）时 {@code note} 是驳回原因，必填。
     */
    public record OnboardingReviewReq(Boolean approve, String note, String reviewBy) {
    }

    /** 生命周期行，镜像前端 {@code SiteLifecycle}。 */
    public record SiteLifecycle(String siteNo, String siteName, String stage, String stageAt,
                                String owner, String currency, BigDecimal gmvLtm) {
    }

    /**
     * 阶段流转入参。
     *
     * <p>{@code gmvLtm} 由调用方（页面/定时任务）在流转时刻算好传入 —— 它是**阶段决策快照**，
     * 服务端只负责落库，**不做定时回刷**（[db-design §3.4] 注）。留空则沿用上一次的值。
     */
    public record StageChangeReq(String stage, String reason, String operator,
                                 BigDecimal gmvLtm, String currency) {
    }

}
