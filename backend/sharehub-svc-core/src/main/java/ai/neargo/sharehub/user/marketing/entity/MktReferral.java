package ai.neargo.sharehub.user.marketing.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 邀请裂变记录（mkt_referral，[db-design §6.3]，C-SH-01/03）。
 *
 * <p>归因唯一性由 DDL 的 {@code UK(tenant_id, invitee_no)} 兜底 ——
 * **一个新用户只能被一个人邀请成功**，这是反刷单的第一道闸；
 * 服务层再撞库时应返回既有记录而不是新建（幂等）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mkt_referral")
public class MktReferral extends BaseEntity {

    private String inviteNo;

    /** 邀请人 c_user_no。 */
    private String inviterNo;

    /** 被邀请人 c_user_no；注册后回填，空 = 链接已发未转化。 */
    private String inviteeNo;

    private BigDecimal reward;

    private String currency;

    /** PENDING / REWARDED。 */
    private String status;
}
