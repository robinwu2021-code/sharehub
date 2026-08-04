package ai.neargo.sharehub.user.member.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 会员等级权益（{@code mbr_benefit}）。
 *
 * <p><b>权益必须随等级单调变好</b>（写入时由 service 强制）——
 * 黄金比铂金还便宜的话，会员体系当场失去意义。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mbr_benefit")
public class MbrBenefit extends BaseEntity {

    /** SILVER / GOLD / PLATINUM，由低到高。 */
    private String level;
    private String name;

    /** 租金折扣；0.9 = 九折，1 = 不打折。**越高等级应越小**。 */
    private BigDecimal rentDiscount;

    /** 每单免费时长（分钟）。**越高等级应越大**。 */
    private Integer freeMinutes;

    private Integer depositFree;
    private Integer monthlyCoupons;
    private BigDecimal pointsRate;

    /** 升到本级所需累计积分；最低档为 0。**越高等级应越大**。 */
    private Integer upgradePoints;

    private String status;
}
