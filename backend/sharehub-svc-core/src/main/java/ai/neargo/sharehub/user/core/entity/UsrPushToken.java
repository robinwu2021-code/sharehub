package ai.neargo.sharehub.user.core.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Push token 注册（usr_push_token，[db-design §6.6]，C-MS-01）。
 * UK(platform, token) —— 同一 token 换绑用户时是 UPDATE 不是 INSERT。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_push_token")
public class UsrPushToken extends BaseEntity {

    private String cUserNo;

    /** APNS / FCM / UNIPUSH。 */
    private String platform;

    private String token;

    private String deviceId;

    /** TINYINT(1)：卸载/登出置 0，不删行（保留触达历史归因）。 */
    private Integer active;
}
