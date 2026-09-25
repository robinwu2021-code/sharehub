package ai.neargo.sharehub.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 结算调整项（stl_adjustment，V107）：撤场结清押金 / 进场费，走结算单，不走提现。 */
@Data
@TableName("stl_adjustment")
public class StlAdjustment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String adjNo;
    private String tenantId;
    private String payeeType;
    private String payeeNo;
    private String payeeName;
    /** {@link ai.neargo.sharehub.finance.AdjustmentKind}。 */
    private String kind;
    private String siteNo;
    private String contractNo;
    /** 对收款方应付的调整：正 = 平台应付对方，负 = 对方应返还平台。 */
    private BigDecimal amount;
    private BigDecimal suggestedAmount;
    private String currency;
    /** {@link ai.neargo.sharehub.finance.AdjustmentStatus}。 */
    private String status;
    private String settleNo;
    private String source;
    /** 按账期的调整（保底补差）填 YYYY-MM；一次性的为空串。 */
    private String period;
    private String note;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    @Version
    private Long version;
}
