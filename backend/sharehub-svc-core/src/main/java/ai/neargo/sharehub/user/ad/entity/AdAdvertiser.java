package ai.neargo.sharehub.user.ad.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 广告主（ad_advertiser，[db-design §6.4]）。
 *
 * <p>{@link AdCampaign} 同时持 {@code advertiserNo}（权威）与 {@code advertiser}（展示名快照），
 * 遵循 [db-design §1.4]「存 _no 为准 + 冗余 _name 供列表直出，不随源改名回溯」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ad_advertiser")
public class AdAdvertiser extends BaseEntity {

    private String advertiserNo;

    private String name;

    private String contact;
}
