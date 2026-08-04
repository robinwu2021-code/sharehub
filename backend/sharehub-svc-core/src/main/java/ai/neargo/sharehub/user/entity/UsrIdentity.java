package ai.neargo.sharehub.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * C 端多渠道身份绑定（usr_identity）。App/小程序/H5 各渠道身份落此表，
 * {@code union_key}(微信 unionid) 归并同一 {@code c_user_no}。db-design §六 + TDD-实现细节 Part B。
 */
@Data
@TableName("usr_identity")
public class UsrIdentity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String cUserNo;
    private String tenantId;
    private String provider;      // WECHAT_MP/WECHAT_OA/APPLE/GOOGLE/PHONE
    private String providerUid;   // openid / apple·google sub / hash(phone)
    private String unionKey;      // 微信 unionid，或 'provider:uid'
    private LocalDateTime boundAt;
    private LocalDateTime createdAt;
}
