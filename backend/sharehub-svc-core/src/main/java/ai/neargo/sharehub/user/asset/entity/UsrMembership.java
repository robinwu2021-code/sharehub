package ai.neargo.sharehub.user.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户会员 / 次卡持有（usr_membership，[db-design §6.3]，C-MB-03）。
 *
 * <p><b>不继承 {@code BaseEntity}</b>：DDL（{@code ddl/pb_core-user-ad-workorder.sql}）里本表
 * 没有 {@code version}/{@code deleted}。
 *
 * <p>{@code planNo} 指向 {@link MbrPlan}；{@code level} 是会员等级（与方案的 {@code cardType} 正交：
 * 等级来自成长值/积分，卡型来自买了哪个方案）。
 */
@Data
@TableName("usr_membership")
public class UsrMembership {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务键，前缀 {@code U}（[db-design §1.4.1]）。 */
    private String mbrNo;

    private String tenantId;

    private String cUserNo;

    /** → {@code mbr_plan.plan_no}。 */
    private String planNo;

    /** SILVER / GOLD / PLATINUM。 */
    private String level;

    /** 积分/成长值。 */
    private Integer points;

    /** 仅日期语义（DATE）。 */
    private String startAt;

    private String endAt;

    /** TINYINT(1)：自动续费开关，C-MB-04 可取消。 */
    private Integer autoRenew;

    /** ACTIVE / EXPIRED / CANCELLED。 */
    private String status;

    private String createdAt;

    private String updatedAt;
}
