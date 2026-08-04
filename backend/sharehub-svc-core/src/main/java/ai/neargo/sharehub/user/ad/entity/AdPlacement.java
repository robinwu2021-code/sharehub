package ai.neargo.sharehub.user.ad.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 投放排期（ad_placement，[db-design §6.4]）—— 把「哪个创意」投到「哪个广告位」的哪些时段。
 * 三元组 {@code adNo × creativeNo × slotNo} + {@code schedule} 才构成一次可下发的投放。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ad_placement")
public class AdPlacement extends BaseEntity {

    private String placementNo;

    private String adNo;

    private String creativeNo;

    /** 目标广告位 {@link AdSlot#getSlotNo()}。 */
    private String slotNo;

    /** 排期 JSON（日期区间 / 每日时段 / 播放频次）。 */
    private String schedule;

    private String status;
}
