package ai.neargo.sharehub.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** C 端用户（usr_user，多渠道身份见 {@link UsrIdentity}）。db-design §六。 */
@Data
@TableName("usr_user")
public class UsrUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String cUserNo;
    private String tenantId;
    private String openid;       // 主渠道冗余展示
    private String unionid;      // 冗余
    private String nickname;
    private String avatar;
    private Integer creditScore;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
    @TableLogic
    private Integer deleted;
}
