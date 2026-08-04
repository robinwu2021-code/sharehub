package ai.neargo.sharehub.user.ad.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 广告创意（ad_creative，[db-design §6.4]）—— 一个 {@link AdCampaign} 下的素材，投屏时按排期播放。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ad_creative")
public class AdCreative extends BaseEntity {

    private String creativeNo;

    /** 所属广告活动 {@link AdCampaign#getAdNo()}。 */
    private String adNo;

    private String mediaUrl;

    /** 素材时长（秒）；图片为空。 */
    private Integer duration;

    /** MIME 类型，如 {@code video/mp4}、{@code image/png}。 */
    private String mime;
}
