package ai.neargo.sharehub.user.member.dto;

import java.math.BigDecimal;

/** 会员/信用/发券域的出入参，镜像 ops-web 的同名 interface。 */
public final class MemberDtos {

    private MemberDtos() {
    }

    /** 会员等级权益行。 */
    public record MemberBenefit(String level, String name, BigDecimal rentDiscount,
                                Integer freeMinutes, Boolean depositFree, Integer monthlyCoupons,
                                BigDecimal pointsRate, Integer upgradePoints,
                                String status, String updatedBy, String updatedAt) {
    }

    /** 会员卡（由 usr_membership 投影）。 */
    public record MemberCard(String mbrNo, String cUserNo, String planNo, String level,
                             String startAt, String endAt, String status,
                             Integer points, Boolean autoRenew) {
    }

    /** 发卡入参。 */
    public record MemberCardGrantReq(String cUserNo, String planNo, String level, Integer months) {
    }

    /** 信用分变更流水行。 */
    public record CreditScoreChange(String changeNo, String cUserNo, Integer before, Integer after,
                                    Integer delta, String reason, String operatorName,
                                    String createdAt) {
    }

    /** 调分入参：{@code delta} 有正负，原因**必填**。 */
    public record CreditScoreAdjustReq(Integer delta, String reason) {
    }

    /** 券发放记录行。 */
    public record CouponIssueRecord(String issueNo, String couponNo, String couponName,
                                    String targetType, String targetDesc, Integer quantity,
                                    String operatorName, String createdAt) {
    }
}
