package ai.neargo.sharehub.user.marketing.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 推送触达（mkt_push，[db-design §6.3]）—— App Push / 小程序订阅消息的批量下发单。
 *
 * <p>{@code sentAt} 为空的语义统一是「尚未发生」（[db-design §1.5]），即 status=DRAFT。
 * 实际下发要走触达渠道并落 {@code notify_log}（platform 域），本表只管「发了什么、发给谁、发了多少」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mkt_push")
public class MktPush extends BaseEntity {

    private String pushNo;

    private String title;

    private String content;

    /** APP_PUSH / SUBSCRIBE。 */
    private String channel;

    /** 受众条件 JSON（分群 SEG / 标签 / 全量）。 */
    private String audience;

    /** 实发人数（下发后回填）。 */
    private Integer sentCount;

    /** DRAFT / SENT。 */
    private String status;

    /** 下发时刻；空 = 尚未发送。 */
    private String sentAt;
}
