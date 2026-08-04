package ai.neargo.sharehub.user.core.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户黑名单（usr_blacklist，[db-design §6.1]）。
 *
 * <p><b>为什么是独立表而不是 {@code usr_credit.blacklisted} 布尔列</b>：
 * 拉黑必须可解除、且解除要留痕（谁在什么时候解的），单列布尔一解除就把证据抹了。
 * 故 <b>解除 = 写 {@code released_at}/{@code released_by} + {@code status=RELEASED}，
 * 记录保留不物理删</b>（见 {@code UserBlacklistService#release}）。
 *
 * <p>业务键前缀 {@code BL} —— 注意与触达拉黑 {@code NBL} 区分（[BizKey]）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_blacklist")
public class UsrBlacklist extends BaseEntity {

    private String blacklistNo;

    private String regionId;

    private String cUserNo;

    private String reason;

    private String blacklistedAt;

    /** 操作人 employee_no。 */
    private String blacklistedBy;

    /** 空 = 尚未解除。 */
    private String releasedAt;

    private String releasedBy;

    /** ACTIVE / RELEASED。 */
    private String status;
}
