package ai.neargo.sharehub.user.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信用分变更流水（{@code usr_credit_change}）。**append 表**，无 version/deleted。
 *
 * <p>「分是怎么变到今天这个数的」是风控争议时唯一的依据，所以只追加不修改。
 */
@Data
@TableName("usr_credit_change")
public class UsrCreditChange {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String changeNo;
    private String tenantId;
    private String cUserNo;
    private Integer scoreBefore;
    private Integer scoreAfter;

    /** after - before；正=加分，负=减分。 */
    private Integer delta;

    /** **必填** —— 没有原因的调分等于没有记录。 */
    private String reason;

    /** 服务端取登录态，不信入参。 */
    private String operatorName;

    private LocalDateTime createdAt;
    private String createdBy;
}
