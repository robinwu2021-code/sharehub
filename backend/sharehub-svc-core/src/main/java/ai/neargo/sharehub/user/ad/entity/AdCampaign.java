package ai.neargo.sharehub.user.ad.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 广告活动（ad_campaign，[db-design §6.4]）—— 广告主的一次投放计划。
 *
 * <p><b>与 {@code mkt_campaign}（营销活动）是两个实体</b>，业务键前缀也不同：本表 {@code AD}，那边 {@code CMP}。
 *
 * <p>{@code budget} 是金额，按 [db-design §1.5]「金额一律带 currency」补 {@code currency}
 * （§6.4 的关键列表未列，属文档遗漏，见交付报告）。
 *
 * <p>广告收入未来纳入分润（{@code share_record.biz_type=AD}），首期只建表不接分润。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ad_campaign")
public class AdCampaign extends BaseEntity {

    /** 业务键，前缀 {@code AD}。注意不叫 campaign_no —— 那是 mkt_campaign 的键名。 */
    private String adNo;

    /** 广告主业务键（权威）。 */
    private String advertiserNo;

    /** 广告主展示名快照（列表直出，不回溯）。 */
    private String advertiser;

    /** 主创意展示名快照；明细在 {@link AdCreative}。 */
    private String creative;

    private BigDecimal budget;

    private String currency;

    /** 定向条件 JSON（region / site / scene）。 */
    private String targeting;

    private String startAt;

    private String endAt;

    /** DRAFT / RUNNING / ENDED。 */
    private String status;
}
