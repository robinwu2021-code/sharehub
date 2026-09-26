package ai.neargo.sharehub.platform.cred.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 登录凭据（V114 建表，B2 起有调用方）。员工与代理共用，{@code realm} 区分。 */
@Getter
@Setter
@TableName("cred_credential")
public class CredCredential extends BaseEntity {

    private String credNo;
    /** STAFF / AGENT。 */
    private String realm;
    /** 主体业务号：员工号 / 代理主体号。 */
    private String subjectNo;
    private String algo;
    /** 口令散列。**绝不存明文，也绝不进日志**。 */
    private String hash;
    /** 1 = 下次登录必须改密（建号发的一次性口令）。 */
    private Integer mustChange;
    /** 连续失败次数，成功即清零。 */
    private Integer failedCount;
    /** 锁定到期时刻；空 = 未锁定。**锁定期内口令正确也拒**。 */
    private LocalDateTime lockedUntil;
    private LocalDateTime pwdChangedAt;
    /** ACTIVE / DISABLED。 */
    private String status;
}
