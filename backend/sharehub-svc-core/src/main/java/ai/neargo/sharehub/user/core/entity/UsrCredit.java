package ai.neargo.sharehub.user.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户信用 / 风控画像（usr_credit，[db-design §6.1]）。
 *
 * <p><b>不继承 {@code BaseEntity}</b>：本表在 DDL 里没有 {@code version}/{@code deleted}
 * （见 {@code ddl/pb_core-user-ad-workorder.sql} + {@code pb_core-v2-alter.sql} 的 ALTER），
 * 继承会让 MP 拼出不存在的列。
 *
 * <p><b>{@code blacklisted} 列已废弃</b>：v2 把「是否拉黑」拆到独立表 {@link UsrBlacklist}
 * （需要 {@code released_at}/{@code released_by} 做解除留痕，单列布尔存不下），
 * 故本实体<b>不声明</b> {@code blacklisted}；旧列在双写期结束后由 DDL 批次 DROP。
 */
@Data
@TableName("usr_credit")
public class UsrCredit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务键，前缀 {@code RK}（[db-design §1.4.1]）。 */
    private String riskNo;

    private String tenantId;

    private String cUserNo;

    /** 信用分。 */
    private Integer score;

    /** HIGH / MEDIUM / LOW。 */
    private String riskLevel;

    private String reason;

    /** 标记为风险用户的时间。 */
    private String flaggedAt;

    private String createdAt;

    private String updatedAt;
}
