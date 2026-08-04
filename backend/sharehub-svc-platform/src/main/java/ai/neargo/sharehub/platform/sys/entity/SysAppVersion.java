package ai.neargo.sharehub.platform.sys.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * C端应用版本（sys_app_version）—— 按平台分 + 灰度比例 + 三语更新说明。
 *
 * <p>业务键 {@code versionId} 是**复合自然键** {@code PLATFORM-versionNo}（如 {@code IOS-1.4.2}）：
 * 同一个 {@code versionNo} 在不同平台会重复，单用它做行键会撞。新建时若未给，
 * 由 service 按 {@code platform + "-" + versionNo} 拼出（见 {@code AppVersionServiceImpl#save}）。
 *
 * <p><b>回滚是软的</b>：{@code status=ROLLBACK} + {@code rolloutPercent=0}，**记录保留**——
 * 灰度出事后要能回看当时发了什么、发给了多少人，物理删就查不出来了。
 *
 * <p>{@code rolloutPercent} 是**百分数口径 0..100**，不是 0..1（[db-design §1.5]）。
 * {@code releasedAt} 用 String 承载（与 wo/dev 域时间列一致，避免序列化格式分歧）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_app_version")
public class SysAppVersion extends BaseEntity {
    /** 自然键 {@code PLATFORM-versionNo}。 */
    private String versionId;
    private String versionNo;
    /** IOS/ANDROID/H5。 */
    private String platform;
    private Integer buildNo;
    private String releaseNote;
    private String releaseNoteEn;
    private String releaseNoteAr;
    private Integer forceUpdate;
    /** 最低可用版本，低于此版强更。 */
    private String minSupported;
    /** 灰度百分比 0..100。 */
    private BigDecimal rolloutPercent;
    private String downloadUrl;
    /** DRAFT/RELEASED/ROLLBACK。 */
    private String status;
    /** 发布时刻；空 = 尚未发生。 */
    private String releasedAt;
}
