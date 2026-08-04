package ai.neargo.sharehub.user.marketing.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 营销活动（mkt_campaign，[db-design §6.3]）。
 *
 * <p><b>务必与 {@code ad_campaign}（广告活动）区分</b>：两者都叫 campaign 但是两个实体 ——
 * 本表是面向 C 端用户的营销玩法（新人礼/充值送/裂变），业务键前缀 {@code CMP}；
 * ad_campaign 是广告主投放计划，前缀 {@code AD}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mkt_campaign")
public class MktCampaign extends BaseEntity {

    private String campaignNo;

    private String name;

    /** NEW_USER / RECHARGE_GIFT / COUPON_PUSH / REFERRAL / FESTIVAL。 */
    private String kind;

    /** 活动规则 JSON（门槛 / 奖励 / 频次）。 */
    private String rule;

    /** DRAFT / RUNNING / ENDED。 */
    private String status;

    private String startAt;

    private String endAt;
}
