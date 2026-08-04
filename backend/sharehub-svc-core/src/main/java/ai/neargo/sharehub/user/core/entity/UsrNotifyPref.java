package ai.neargo.sharehub.user.core.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * C 端通知偏好（usr_notify_pref，[db-design §6.6]，C-MS-04）。
 * 一行 = 一个分类的开关；UK(c_user_no, category)。
 * 免打扰窗口按 [db-design §1.5] 用 {@code CHAR(5)} 的 {@code HH:mm}，不是时间戳。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_notify_pref")
public class UsrNotifyPref extends BaseEntity {

    private String cUserNo;

    /** ORDER / WALLET / COUPON / SYSTEM / ACTIVITY / CS。 */
    private String category;

    /** TINYINT(1)。 */
    private Integer enabled;

    /** 静默起 HH:mm。 */
    private String quietStart;

    /** 静默止 HH:mm。 */
    private String quietEnd;

    /** zh / en / ar。 */
    private String lang;
}
