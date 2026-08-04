package ai.neargo.sharehub.user.marketing.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 邀请裂变规则（{@code mkt_referral_rule}，V31/V33）。
 * {@code mkt_referral} 是邀请**记录**，本表是**规则** —— referral-rules 端点此前翻记录表出错数据（D-3）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mkt_referral_rule")
public class MktReferralRule extends BaseEntity {

    private String ruleNo;
    private String name;

    /** 单侧奖励金额（BOTH 时双方各得此额，不是均分）。列名沿用 V31 的 inviter_reward。 */
    private BigDecimal inviterReward;

    private String currency;

    /** 奖励对象：INVITER / INVITEE / BOTH（V33）。 */
    private String rewardTo;

    /** 触发事件：FIRST_ORDER / REGISTER 等（V33，前端 ReferralTrigger）。 */
    private String triggerEvent;

    /** 每位邀请人最多获奖次数；0=不限。列名沿用 V31 的 cap_per_user。 */
    private Integer capPerUser;

    private java.time.LocalDateTime startAt;
    private java.time.LocalDateTime endAt;

    /** ENABLED / DISABLED（出参映射为前端的 ACTIVE / DISABLED）。 */
    private String status;

    private java.time.LocalDateTime archivedAt;
}
