package ai.neargo.sharehub.platform.notify.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 触达拉黑（notify_blacklist）—— 全渠道，[db-design §2.3]。
 *
 * <p><b>比竞品清晰在哪</b>：对方只有"短信拉黑"；这里 {@code channel} 含 {@code ALL}，
 * 一条记录即可覆盖全部渠道，且 {@code reason} 是枚举而非自由文本，退订来源可统计。
 *
 * <p><b>解除是软删</b>：{@code status=RELEASED} + 回填 {@code releasedAt}/{@code releasedBy}，
 * 记录保留。合规场景要能回答"这个号码何时被谁拉黑、又何时被谁放开"，物理删就答不出来了。
 * {@code expireAt} 是另一条路径（到期自动失效，空 = 永久），与手工解除并存。
 *
 * <p>业务键前缀 {@code NBL}（{@code BizKey.NOTIFY_BLACKLIST}）——
 * 与用户风控黑名单的 {@code BL} 必须区分，两者是不同的业务对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notify_blacklist")
public class NotifyBlacklist extends BaseEntity {
    private String blockNo;
    /** 被拉黑接收方，**脱敏存储**（与 notify_log.target 同口径，才能对上号）。 */
    private String target;
    /** SMS / EMAIL / PUSH / WHATSAPP / ALL */
    private String channel;
    /** USER_OPT_OUT / HARD_BOUNCE / ABUSE / MANUAL */
    private String reason;
    private String blockedAt;
    private String blockedBy;
    /** 到期自动解除（空 = 永久）。 */
    private String expireAt;
    /** 解除时刻（空 = 尚未解除）。 */
    private String releasedAt;
    private String releasedBy;
    /** ACTIVE / RELEASED */
    private String status;
}
