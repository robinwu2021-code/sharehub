package ai.neargo.sharehub.user.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 券发放记录（{@code usr_coupon_issue}）。**append 表**：发过就是发过，不可改不可删。 */
@Data
@TableName("usr_coupon_issue")
public class UsrCouponIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String issueNo;
    private String tenantId;
    private String couponNo;
    private String couponName;

    /** 人群类型 ALL / SEGMENT / USER_LIST。 */
    private String targetType;

    /** 人群口径的可读描述（含规模），如「消费者分层：高频通勤用户（3820 人）」。 */
    private String targetDesc;

    private Integer quantity;
    private String operatorName;
    private LocalDateTime createdAt;
    private String createdBy;
}
